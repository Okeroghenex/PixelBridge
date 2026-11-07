import AppKit
import BridgeCore
import CoreImage
import Darwin

/// Appends a line to ~/Library/Logs/PixelBridge.log (kept under ~1 MB). Never logs message contents.
func pbLog(_ s: String) {
    let url = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/PixelBridge.log")
    let line = "\(ISO8601DateFormatter().string(from: Date())) \(s)\n"
    if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
       (attrs[.size] as? NSNumber)?.intValue ?? 0 > 1_000_000 {
        try? FileManager.default.removeItem(at: url)
    }
    if let h = try? FileHandle(forWritingTo: url) {
        h.seekToEndOfFile(); h.write(Data(line.utf8)); try? h.close()
    } else {
        try? Data(line.utf8).write(to: url)
    }
}

/// Stores the 16-byte pairing secret in ~/Library/Application Support/PixelBridge/pairing.key (mode 0600).
enum SecretStore {
    static var fileURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PixelBridge", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("pairing.key")
    }

    static var isNew = false

    static func load() -> Data {
        if let d = try? Data(contentsOf: fileURL), d.count == 16 { return d }
        isNew = true
        return reset()
    }

    @discardableResult
    static func reset() -> Data {
        let s = Pairing.newSecret()
        try? s.write(to: fileURL, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: fileURL.path)
        return s
    }
}

enum NetworkInfo {
    /// Best-guess LAN IPv4 address (Wi-Fi/Ethernet), used in the pairing QR as a fallback to Bonjour.
    static func lanIPv4() -> String? {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return nil }
        defer { freeifaddrs(ifaddr) }
        var candidates: [(String, String)] = []
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let flags = Int32(ptr.pointee.ifa_flags)
            guard let addr = ptr.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
            let name = String(cString: ptr.pointee.ifa_name)
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                candidates.append((name, String(cString: host)))
            }
        }
        let preferred = candidates.first { $0.0.hasPrefix("en") && !$0.1.hasPrefix("169.254") }
        return (preferred ?? candidates.first { !$0.1.hasPrefix("169.254") })?.1
    }
}

enum QRCode {
    static func image(for string: String, size: CGFloat) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(string.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let scale = size / output.extent.width
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: scaled)
        let img = NSImage(size: rep.size)
        img.addRepresentation(rep)
        return img
    }
}

/// Window that shows the pairing QR code + manual code.
final class PairingWindowController: NSWindowController {
    convenience init(uri: String, code: String, address: String) {
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 380, height: 520),
                              styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "Pair your Android phone"
        window.isReleasedWhenClosed = false
        self.init(window: window)

        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 14
        stack.edgeInsets = NSEdgeInsets(top: 24, left: 24, bottom: 24, right: 24)

        let title = NSTextField(labelWithString: "Scan with the PixelBridge app")
        title.font = .boldSystemFont(ofSize: 16)

        let qr = NSImageView()
        qr.image = QRCode.image(for: uri, size: 260)
        qr.imageScaling = .scaleNone
        qr.wantsLayer = true
        qr.layer?.backgroundColor = NSColor.white.cgColor
        qr.layer?.cornerRadius = 8
        qr.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([qr.widthAnchor.constraint(equalToConstant: 284),
                                     qr.heightAnchor.constraint(equalToConstant: 284)])

        let or = NSTextField(labelWithString: "Or enter this code on the phone:")
        or.textColor = .secondaryLabelColor
        let codeLabel = NSTextField(labelWithString: code)
        codeLabel.font = .monospacedSystemFont(ofSize: 15, weight: .semibold)
        codeLabel.isSelectable = true
        let addr = NSTextField(labelWithString: "Mac address: \(address)")
        addr.textColor = .secondaryLabelColor
        addr.isSelectable = true
        let note = NSTextField(wrappingLabelWithString: "Phone and Mac must be on the same Wi-Fi network. Keep this code private: anyone with it can send to this Mac.")
        note.textColor = .tertiaryLabelColor
        note.font = .systemFont(ofSize: 11)
        note.alignment = .center

        [title, qr, or, codeLabel, addr, note].forEach(stack.addArrangedSubview)
        window.contentView = stack
        window.center()
    }
}
