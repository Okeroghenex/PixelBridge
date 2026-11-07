import BridgeCore
import Foundation
import Network

// Pretends to be the phone. Usage:
//   bridge-test-client <PAIRING-CODE> [host] [port] [--text "hi"] [--file /path] [--wait seconds]
var args = Array(CommandLine.arguments.dropFirst())
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
