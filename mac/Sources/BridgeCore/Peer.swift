import Foundation
import Network

public protocol PeerDelegate: AnyObject {
    func peerDidBecomeReady(_ peer: Peer)
    func peer(_ peer: Peer, didReceive message: Message)
    func peerDidClose(_ peer: Peer, error: Error?)
}

/// One encrypted, framed connection. Used by the Mac app (server side) and by the test client (client side).
/// Frames on the wire: [u32 BE length][payload]. The first frame each way is a plaintext JSON
/// handshake ({"v":1,"nonce":"<base64>"}); everything after is AES-GCM sealed (see SessionCrypto).
public final class Peer {
    public let connection: NWConnection
    public let isServer: Bool
    public weak var delegate: PeerDelegate?
    public private(set) var isReady = false
    public private(set) var remoteName = ""
    public private(set) var remotePlatform = ""
    public private(set) var lastReceived = Date()
    public let queue: DispatchQueue

    private let secret: Data
    private let localName: String
    private let platform: String
    private var crypto: SessionCrypto?
    private var clientNonce = Data()
    private var closed = false

    public init(connection: NWConnection, isServer: Bool, secret: Data, localName: String,
                platform: String, queue: DispatchQueue) {
        self.connection = connection
        self.isServer = isServer
        self.secret = secret
        self.localName = localName
        self.platform = platform
        self.queue = queue
    }

    public var endpointDescription: String { "\(connection.endpoint)" }

    public func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                if !self.isServer {
                    self.clientNonce = Pairing.randomNonce()
                    self.sendRaw(Self.handshakeJSON(nonce: self.clientNonce))
                }
                self.readFrame()
            case .failed(let e):
                self.close(e)
            case .cancelled:
                self.close(nil)
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    public func close(_ error: Error? = nil) {
        queue.async { [self] in
            guard !closed else { return }
            closed = true
            isReady = false
            connection.cancel()
            delegate?.peerDidClose(self, error: error)
        }
    }

    // MARK: Sending

    public func send(_ message: Message, completion: ((Error?) -> Void)? = nil) {
        queue.async { [self] in
            guard let crypto, !closed else { completion?(BridgeError.closed); return }
            do {
                let sealed = try crypto.seal(try message.encode())
                sendRaw(sealed, completion: completion)
            } catch {
                completion?(error)
            }
        }
    }

    /// Streams a file as file_start / file_chunk… / file_end. Chunks are sent one at a time so memory stays flat.
    public func sendFile(at url: URL, name: String? = nil, mime: String = "application/octet-stream",
                         progress: ((Double) -> Void)? = nil, completion: ((Error?) -> Void)? = nil) {
        queue.async { [self] in
            let handle: FileHandle
            let size: Int64
            do {
                handle = try FileHandle(forReadingFrom: url)
                size = (try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
            } catch {
                completion?(error); return
            }
            let id = UUID().uuidString
            send(Message("file_start", ["id": id, "name": name ?? url.lastPathComponent, "size": size, "mime": mime]))
            var sent: Int64 = 0

            func next() {
                let chunk: Data
                do { chunk = try handle.read(upToCount: Bridge.chunkSize) ?? Data() } catch {
                    try? handle.close(); completion?(error); return
                }
                if chunk.isEmpty {
                    try? handle.close()
                    send(Message("file_end", ["id": id]), completion: completion)
                    return
                }
                sent += Int64(chunk.count)
                send(Message("file_chunk", ["id": id], body: chunk)) { err in
                    if let err { try? handle.close(); completion?(err); return }
                    if size > 0 { progress?(Double(sent) / Double(size)) }
                    next()
                }
            }
            next()
        }
    }

    private func sendRaw(_ payload: Data, completion: ((Error?) -> Void)? = nil) {
        var frame = Data(capacity: payload.count + 4)
        frame.appendUInt32(UInt32(payload.count))
        frame.append(payload)
        connection.send(content: frame, completion: .contentProcessed { err in
            completion?(err)
        })
    }

    private static func handshakeJSON(nonce: Data) -> Data {
        try! JSONSerialization.data(withJSONObject: ["v": Bridge.version, "nonce": nonce.base64EncodedString()])
    }

    // MARK: Receiving

    private func readFrame() {
        receiveExactly(4) { [self] lenData in
            let len = Int(lenData.readUInt32(at: 0))
            guard len > 0, len <= Bridge.maxFrame else { close(BridgeError.badFrame("length \(len)")); return }
            receiveExactly(len) { [self] payload in
                lastReceived = Date()
                do {
                    try handleFrame(payload)
                    if !closed { readFrame() }
                } catch {
                    close(error)
                }
            }
        }
    }

    private func receiveExactly(_ n: Int, _ done: @escaping (Data) -> Void) {
        connection.receive(minimumIncompleteLength: n, maximumLength: n) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let error { self.close(error); return }
            guard let data, data.count == n else {
                self.close(isComplete ? nil : BridgeError.closed); return
            }
            done(data)
        }
    }

    private func handleFrame(_ payload: Data) throws {
        guard let crypto else {
            // Handshake frame (plaintext).
            guard let obj = try JSONSerialization.jsonObject(with: payload) as? [String: Any],
                  let b64 = obj["nonce"] as? String, let nonce = Data(base64Encoded: b64), nonce.count >= 16
            else { throw BridgeError.badFrame("handshake") }
            if let v = obj["v"] as? Int, v != Bridge.version { throw BridgeError.badFrame("unsupported version \(v)") }
            if isServer {
                let serverNonce = Pairing.randomNonce()
                sendRaw(Self.handshakeJSON(nonce: serverNonce))
                self.crypto = SessionCrypto(secret: secret, clientNonce: nonce, serverNonce: serverNonce, isServer: true)
            } else {
                self.crypto = SessionCrypto(secret: secret, clientNonce: clientNonce, serverNonce: nonce, isServer: false)
                send(Message("hello", ["name": localName, "platform": platform]))
            }
            return
        }

        let message = try Message.decode(try crypto.open(payload))
        if !isReady {
            guard message.type == "hello" else { throw BridgeError.badFrame("expected hello") }
            remoteName = message.string("name") ?? "Device"
            remotePlatform = message.string("platform") ?? ""
            isReady = true
            if isServer { send(Message("hello", ["name": localName, "platform": platform])) }
            delegate?.peerDidBecomeReady(self)
            return
        }
        switch message.type {
        case "ping": send(Message("pong"))
        case "pong": break
        default: delegate?.peer(self, didReceive: message)
        }
    }
}
