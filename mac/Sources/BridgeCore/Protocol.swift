import CryptoKit
import Foundation

/// Wire protocol shared with the Android app. See PROTOCOL.md at the repo root.
public enum Bridge {
    public static let serviceType = "_pixelbridge._tcp"
    public static let defaultPort: UInt16 = 47820
    public static let version = 1
    public static let maxFrame = 8 * 1024 * 1024
    public static let chunkSize = 256 * 1024
    public static let hkdfInfo = Data("pixelbridge-v1".utf8)
}

public enum BridgeError: Error, CustomStringConvertible {
    case badFrame(String)
    case auth
    case closed

    public var description: String {
        switch self {
        case .badFrame(let s): return "bad frame: \(s)"
        case .auth: return "authentication failed (wrong pairing code?)"
        case .closed: return "connection closed"
        }
    }
}

/// An application message: a JSON header (always has "t") plus an optional binary body.
/// Encoded as [u32 BE header length][header JSON][body].
public struct Message {
    public var header: [String: Any]
    public var body: Data

    public init(_ type: String, _ fields: [String: Any] = [:], body: Data = Data()) {
        var h = fields
        h["t"] = type
        self.header = h
        self.body = body
    }

    public var type: String { header["t"] as? String ?? "" }
    public func string(_ k: String) -> String? { header[k] as? String }
    public func int(_ k: String) -> Int64? { (header[k] as? NSNumber)?.int64Value }

    public func encode() throws -> Data {
        let json = try JSONSerialization.data(withJSONObject: header)
        var out = Data(capacity: 4 + json.count + body.count)
        out.appendUInt32(UInt32(json.count))
        out.append(json)
        out.append(body)
        return out
    }

    public static func decode(_ data: Data) throws -> Message {
        guard data.count >= 4 else { throw BridgeError.badFrame("short message") }
        let hlen = Int(data.readUInt32(at: 0))
        guard hlen <= data.count - 4 else { throw BridgeError.badFrame("header length") }
        let start = data.startIndex
        let json = data.subdata(in: (start + 4)..<(start + 4 + hlen))
        guard let header = try JSONSerialization.jsonObject(with: json) as? [String: Any] else {
            throw BridgeError.badFrame("header not an object")
        }
        let body = data.subdata(in: (start + 4 + hlen)..<data.endIndex)
        var m = Message("", body: body)
        m.header = header
        return m
    }
}

/// Per-connection AES-256-GCM session.
/// key = HKDF-SHA256(ikm: pairing secret, salt: clientNonce || serverNonce, info: "pixelbridge-v1", 32 bytes)
/// nonce = [u32 BE direction][u64 BE counter]; direction 0 = client->server, 1 = server->client.
/// Counters start at 0 and must arrive in order, so replayed or reordered frames fail to decrypt.
public final class SessionCrypto {
    private let key: SymmetricKey
    private let sendDir: UInt32
    private let recvDir: UInt32
    private var sendCounter: UInt64 = 0
    private var recvCounter: UInt64 = 0
    private let lock = NSLock()

    public init(secret: Data, clientNonce: Data, serverNonce: Data, isServer: Bool) {
        key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: secret),
            salt: clientNonce + serverNonce,
            info: Bridge.hkdfInfo,
            outputByteCount: 32)
        sendDir = isServer ? 1 : 0
        recvDir = isServer ? 0 : 1
    }

    private static func nonce(_ dir: UInt32, _ counter: UInt64) -> AES.GCM.Nonce {
        var d = Data()
        d.appendUInt32(dir)
        d.appendUInt64(counter)
        return try! AES.GCM.Nonce(data: d)
    }

    /// Returns ciphertext || 16-byte tag.
    public func seal(_ plain: Data) throws -> Data {
        lock.lock(); defer { lock.unlock() }
        let box = try AES.GCM.seal(plain, using: key, nonce: Self.nonce(sendDir, sendCounter))
        sendCounter += 1
        return box.ciphertext + box.tag
    }

    public func open(_ frame: Data) throws -> Data {
        lock.lock(); defer { lock.unlock() }
        guard frame.count >= 16 else { throw BridgeError.auth }
        let ct = frame.prefix(frame.count - 16)
        let tag = frame.suffix(16)
        do {
            let box = try AES.GCM.SealedBox(nonce: Self.nonce(recvDir, recvCounter), ciphertext: ct, tag: tag)
            let plain = try AES.GCM.open(box, using: key)
            recvCounter += 1
            return plain
        } catch {
            throw BridgeError.auth
        }
    }
}

public enum Pairing {
    public static func newSecret() -> Data {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes)
    }

    public static func randomNonce() -> Data {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes)
    }

    /// Human-friendly code: RFC 4648 base32 (no padding) in groups of 4 → "ABCD-EFGH-…" (26 chars).
    public static func code(for secret: Data) -> String {
        let raw = Base32.encode(secret)
        var groups: [String] = []
        var i = raw.startIndex
        while i < raw.endIndex {
            let j = raw.index(i, offsetBy: 4, limitedBy: raw.endIndex) ?? raw.endIndex
            groups.append(String(raw[i..<j]))
            i = j
        }
        return groups.joined(separator: "-")
    }

    public static func secret(fromCode code: String) -> Data? {
        let cleaned = code.uppercased().filter { $0.isLetter || $0.isNumber }
        guard let d = Base32.decode(cleaned), d.count == 16 else { return nil }
        return d
    }

    public static func uri(secret: Data, host: String?, port: UInt16, name: String) -> String {
        var c = URLComponents()
        c.scheme = "pixelbridge"
        c.host = "pair"
        var items = [URLQueryItem(name: "v", value: "\(Bridge.version)"),
                     URLQueryItem(name: "k", value: Base32.encode(secret)),
                     URLQueryItem(name: "p", value: "\(port)"),
                     URLQueryItem(name: "n", value: name)]
        if let host { items.append(URLQueryItem(name: "h", value: host)) }
        c.queryItems = items
        return c.string ?? ""
    }
}

public enum Base32 {
    private static let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567")

    public static func encode(_ data: Data) -> String {
        var out = ""
        var buffer = 0, bits = 0
        for byte in data {
            buffer = (buffer << 8) | Int(byte)
            bits += 8
            while bits >= 5 {
                out.append(alphabet[(buffer >> (bits - 5)) & 31])
                bits -= 5
            }
        }
        if bits > 0 { out.append(alphabet[(buffer << (5 - bits)) & 31]) }
        return out
    }

    public static func decode(_ s: String) -> Data? {
        var out = Data()
        var buffer = 0, bits = 0
        for ch in s {
            guard let v = alphabet.firstIndex(of: ch) else { return nil }
            buffer = (buffer << 5) | v
            bits += 5
            if bits >= 8 {
                out.append(UInt8((buffer >> (bits - 8)) & 0xFF))
                bits -= 8
            }
        }
        return out
    }
}

extension Data {
    mutating func appendUInt32(_ v: UInt32) {
        var be = v.bigEndian
        Swift.withUnsafeBytes(of: &be) { append(contentsOf: $0) }
    }

    mutating func appendUInt64(_ v: UInt64) {
        var be = v.bigEndian
        Swift.withUnsafeBytes(of: &be) { append(contentsOf: $0) }
    }

    func readUInt32(at offset: Int) -> UInt32 {
        let s = startIndex + offset
        return self[s..<(s + 4)].reduce(0) { ($0 << 8) | UInt32($1) }
    }
}
