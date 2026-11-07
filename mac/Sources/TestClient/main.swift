import BridgeCore
import Foundation
import Network

// Pretends to be the phone. Usage:
//   bridge-test-client <PAIRING-CODE> [host] [port] [--text "hi"] [--file /path] [--wait seconds]
var args = Array(CommandLine.arguments.dropFirst())

// "--serve <name> <port> <seconds>": act as an extra Mac (own secret) so a phone can pair with two Macs.
if args.first == "--serve" {
    let name = args.count > 1 ? args[1] : "Test Mac"
    let port = UInt16(args.count > 2 ? args[2] : "47821") ?? 47821
    let seconds = Double(args.count > 3 ? args[3] : "120") ?? 120
    let secret = Pairing.newSecret()
    print("URI " + Pairing.uri(secret: secret, host: "10.0.2.2", port: port, name: name))
    final class Server: PeerDelegate {
        var peers: [Peer] = []
        func peerDidBecomeReady(_ p: Peer) { print("SERVE ready \(p.remoteName)") }
        func peer(_ p: Peer, didReceive m: Message) { print("SERVE recv \(m.type)") }
        func peerDidClose(_ p: Peer, error: Error?) { print("SERVE closed") }
    }
    let server = Server()
    let q = DispatchQueue(label: "serve")
    let listener = try! NWListener(using: .tcp, on: NWEndpoint.Port(rawValue: port)!)
    listener.newConnectionHandler = { c in
        let p = Peer(connection: c, isServer: true, secret: secret, localName: name, platform: "mac", queue: q)
        p.delegate = server
        server.peers.append(p)
        p.start()
    }
    listener.start(queue: q)
    setvbuf(stdout, nil, _IOLBF, 0)
    Thread.sleep(forTimeInterval: seconds)
    exit(0)
}
// "--keyfile" reads the Mac app's stored secret directly (handy for local testing).
if args.first == "--keyfile" {
    let url = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Application Support/PixelBridge/pairing.key")
    if let d = try? Data(contentsOf: url) { args[0] = Pairing.code(for: d) }
}
guard let code = args.first, let secret = Pairing.secret(fromCode: code) else {
    print("usage: bridge-test-client <PAIRING-CODE> [host] [port] [--text s] [--file path] [--wait secs]")
    exit(2)
}
args.removeFirst()
var host = "127.0.0.1", port = Bridge.defaultPort
var texts: [String] = [], files: [String] = [], wait = 3.0
var positional: [String] = []
var i = 0
while i < args.count {
    switch args[i] {
    case "--text": texts.append(args[i + 1]); i += 2
    case "--file": files.append(args[i + 1]); i += 2
    case "--wait": wait = Double(args[i + 1]) ?? wait; i += 2
    default: positional.append(args[i]); i += 1
    }
}
if positional.count > 0 { host = positional[0] }
if positional.count > 1, let p = UInt16(positional[1]) { port = p }

final class Client: PeerDelegate {
    let done = DispatchSemaphore(value: 0)
    var ready = false
    func peerDidBecomeReady(_ peer: Peer) {
        print("READY remote=\(peer.remoteName) platform=\(peer.remotePlatform)")
        ready = true
        done.signal()
    }
    func peer(_ peer: Peer, didReceive m: Message) {
        let preview = m.type == "clip_text" ? (String(data: m.body, encoding: .utf8) ?? "") : "\(m.body.count) bytes"
        print("RECV \(m.type) \(m.header.filter { $0.key != "t" }) \(preview)")
    }
    func peerDidClose(_ peer: Peer, error: Error?) {
        print("CLOSED \(error.map { "\($0)" } ?? "")")
        done.signal()
    }
}

let client = Client()
let q = DispatchQueue(label: "client")
let conn = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
let peer = Peer(connection: conn, isServer: false, secret: secret, localName: "Test Phone", platform: "android", queue: q)
peer.delegate = client
peer.start()
if client.done.wait(timeout: .now() + 5) == .timedOut || !client.ready { print("FAILED to connect/auth"); exit(1) }

peer.send(Message("ping"))
for t in texts {
    let s = DispatchSemaphore(value: 0)
    peer.send(Message("clip_text", body: Data(t.utf8))) { _ in s.signal() }
    s.wait()
    print("SENT clip_text \(t)")
}
for f in files {
    let s = DispatchSemaphore(value: 0)
    peer.sendFile(at: URL(fileURLWithPath: f), completion: { err in print("SENT file \(f) err=\(String(describing: err))"); s.signal() })
    s.wait()
}
Thread.sleep(forTimeInterval: wait)
peer.close()
Thread.sleep(forTimeInterval: 0.2)
