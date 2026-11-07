import AppKit
import BridgeCore
import Foundation
import Network

/// Listens on the LAN (advertised over Bonjour) and keeps authenticated phone connections (one per device).
final class BridgeServer: PeerDelegate {
    enum State: Equatable {
        case starting
        case waiting(port: UInt16)
        case connected(name: String)
        case failed(String)
    }

    let queue = DispatchQueue(label: "pixelbridge.server")
    private var listener: NWListener?
    private var pending: [ObjectIdentifier: Peer] = [:]
    private var phones: [Peer] = []
    private var receivers: [String: IncomingFile] = [:]
    private var watchdog: DispatchSourceTimer?
    private let secretProvider: () -> Data

    var onStateChange: ((State) -> Void)?
    var onMessage: ((Message) -> Void)?
    var onFileReceived: ((URL) -> Void)?
    var onLog: ((String) -> Void)?

    private(set) var state: State = .starting {
        didSet {
            let s = state
            DispatchQueue.main.async { self.onStateChange?(s) }
        }
    }

    var hasPhone: Bool { queue.sync { !phones.isEmpty } }

    var port: UInt16 { listener?.port?.rawValue ?? Bridge.defaultPort }

    init(secretProvider: @escaping () -> Data) {
        self.secretProvider = secretProvider
    }

    func start() {
        queue.async { [self] in
            startListener(port: Bridge.defaultPort)
            startWatchdog()
        }
    }

    /// Drops the current phone (e.g. after the pairing key was reset).
    func disconnectAll() {
        queue.async { [self] in
            phones.forEach { $0.close() }
            pending.values.forEach { $0.close() }
        }
    }

    private func startListener(port: UInt16) {
        let params = NWParameters.tcp
        params.includePeerToPeer = false
        if let opts = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options {
            opts.noDelay = true
            opts.enableKeepalive = true
            opts.keepaliveIdle = 15
        }
        do {
            let l = try NWListener(using: params, on: NWEndpoint.Port(rawValue: port)!)
            l.service = NWListener.Service(name: Host.current().localizedName ?? "Mac", type: Bridge.serviceType)
            l.stateUpdateHandler = { [weak self] st in
                guard let self else { return }
                switch st {
                case .ready:
                    self.log("Listening on port \(l.port?.rawValue ?? 0)")
                    if self.phones.isEmpty { self.state = .waiting(port: l.port?.rawValue ?? port) }
                case .failed(let err):
                    self.log("Listener failed: \(err)")
                    l.cancel()
                    if port == Bridge.defaultPort {
                        // Port busy: fall back to any free port. Bonjour still advertises it.
                        self.startListener(port: 0)
                    } else {
                        self.state = .failed("\(err)")
                    }
                default: break
                }
            }
            l.newConnectionHandler = { [weak self] conn in self?.accept(conn) }
            l.start(queue: queue)
            listener = l
        } catch {
            if port == Bridge.defaultPort { startListener(port: 0) } else { state = .failed("\(error)") }
        }
    }

    private func accept(_ conn: NWConnection) {
        let peer = Peer(connection: conn, isServer: true, secret: secretProvider(),
                        localName: Host.current().localizedName ?? "Mac", platform: "mac", queue: queue)
        peer.delegate = self
        pending[ObjectIdentifier(peer)] = peer
        log("Incoming connection from \(conn.endpoint)")
        peer.start()
    }

    private func startWatchdog() {
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 10, repeating: 10)
        t.setEventHandler { [weak self] in
            guard let self else { return }
            let now = Date()
            // The phone pings every 20s; drop silent connections so a new one can take over.
            for p in self.phones where now.timeIntervalSince(p.lastReceived) > 70 {
                self.log("\(p.remoteName) went silent; dropping connection")
                p.close()
            }
            for p in self.pending.values where now.timeIntervalSince(p.lastReceived) > 15 { p.close() }
        }
        t.resume()
        watchdog = t
    }

    // MARK: Sending

    func send(_ message: Message) {
        queue.async { [self] in phones.forEach { $0.send(message) } }
    }

    func sendFiles(_ urls: [URL], progress: ((String, Double) -> Void)? = nil, completion: ((Bool) -> Void)? = nil) {
        queue.async { [self] in
            let targets = phones
            guard !targets.isEmpty else { DispatchQueue.main.async { completion?(false) }; return }
            let group = DispatchGroup()
            var allOK = true
            for (i, phone) in targets.enumerated() {
                group.enter()
                var remaining = urls
                func next() {
                    guard !remaining.isEmpty else { group.leave(); return }
                    let url = remaining.removeFirst()
                    phone.sendFile(at: url, mime: MimeTypes.forURL(url), progress: { p in
                        // Report progress for the first phone only, to keep the menu readable.
                        if i == 0 { DispatchQueue.main.async { progress?(url.lastPathComponent, p) } }
                    }, completion: { err in
                        if let err {
                            self.log("Sending \(url.lastPathComponent) to \(phone.remoteName) failed: \(err)")
                            self.queue.async { allOK = false; group.leave() }
                            return
                        }
                        next()
                    })
                }
                next()
            }
            group.notify(queue: queue) { DispatchQueue.main.async { completion?(allOK) } }
        }
    }

    // MARK: PeerDelegate (called on `queue`)

    func peerDidBecomeReady(_ peer: Peer) {
        pending.removeValue(forKey: ObjectIdentifier(peer))
        // A device that reconnects replaces only its own stale connection.
        for old in phones where old.remoteName == peer.remoteName {
            old.delegate = nil
            old.close()
        }
        phones.removeAll { $0.remoteName == peer.remoteName }
        phones.append(peer)
        log("Paired device connected: \(peer.remoteName)")
        updateState()
    }

    func peer(_ peer: Peer, didReceive message: Message) {
        guard phones.contains(where: { $0 === peer }) else { return }
        switch message.type {
        case "file_start":
            guard let id = message.string("id") else { return }
            do {
                receivers[key(peer, id)] = try IncomingFile(name: message.string("name") ?? "file",
                                                 expectedSize: message.int("size") ?? -1)
            } catch {
                log("Can't save incoming file: \(error)")
            }
        case "file_chunk":
            guard let id = message.string("id") else { return }
            receivers[key(peer, id)]?.write(message.body)
        case "file_end":
            guard let id = message.string("id"), let r = receivers.removeValue(forKey: key(peer, id)) else { return }
            if let url = r.finish() {
                DispatchQueue.main.async { self.onFileReceived?(url) }
            }
        default:
            DispatchQueue.main.async { self.onMessage?(message) }
        }
    }

    func peerDidClose(_ peer: Peer, error: Error?) {
        pending.removeValue(forKey: ObjectIdentifier(peer))
        if let error { log("Connection closed: \(error)") }
        if phones.contains(where: { $0 === peer }) {
            phones.removeAll { $0 === peer }
            let prefix = "\(ObjectIdentifier(peer).hashValue)/"
            for (k, r) in receivers where k.hasPrefix(prefix) {
                r.abort()
                receivers.removeValue(forKey: k)
            }
            updateState()
        }
    }

    private func key(_ peer: Peer, _ id: String) -> String { "\(ObjectIdentifier(peer).hashValue)/\(id)" }

    private func updateState() {
        if phones.isEmpty { state = .waiting(port: port) }
        else { state = .connected(name: phones.map(\.remoteName).joined(separator: ", ")) }
    }

    private func log(_ s: String) {
        NSLog("[PixelBridge] %@", s)
        pbLog(s)
        DispatchQueue.main.async { self.onLog?(s) }
    }
}

/// Writes an incoming file to ~/Downloads as "<name>.pixelbridge-part", then renames it when complete.
final class IncomingFile {
    let finalURL: URL
    let partURL: URL
    let handle: FileHandle
    let expectedSize: Int64
    private var written: Int64 = 0

    init(name: String, expectedSize: Int64) throws {
        let safe = (name as NSString).lastPathComponent.replacingOccurrences(of: ":", with: "-")
        let dir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        finalURL = IncomingFile.uniqueURL(in: dir, name: safe.isEmpty ? "file" : safe)
        partURL = finalURL.appendingPathExtension("pixelbridge-part")
        FileManager.default.createFile(atPath: partURL.path, contents: nil)
        handle = try FileHandle(forWritingTo: partURL)
        self.expectedSize = expectedSize
    }

    func write(_ d: Data) {
        try? handle.write(contentsOf: d)
        written += Int64(d.count)
    }

    func finish() -> URL? {
        try? handle.close()
        if expectedSize >= 0 && written != expectedSize {
            try? FileManager.default.removeItem(at: partURL)
            return nil
        }
        do {
            try FileManager.default.moveItem(at: partURL, to: finalURL)
            return finalURL
        } catch {
            return nil
        }
    }

    func abort() {
        try? handle.close()
        try? FileManager.default.removeItem(at: partURL)
    }

    static func uniqueURL(in dir: URL, name: String) -> URL {
        var url = dir.appendingPathComponent(name)
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var n = 2
        while FileManager.default.fileExists(atPath: url.path) {
            url = dir.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)")
            n += 1
        }
        return url
    }
}

enum MimeTypes {
    static func forURL(_ url: URL) -> String {
        if let t = try? url.resourceValues(forKeys: [.contentTypeKey]).contentType,
           let mime = t.preferredMIMEType {
            return mime
        }
        return "application/octet-stream"
    }
}
