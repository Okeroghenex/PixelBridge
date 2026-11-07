import AppKit

/// Test-only remote control, enabled by launching with PIXELBRIDGE_DEBUG=<folder for snapshots>.
/// Lets an automated test press buttons and snapshot windows without driving the real screen:
///   DistributedNotificationCenter.post("app.pixelbridge.debug", object: "snapshot" | "decline" | "messages" | "reply:<text>")
enum DebugHooks {
    static var lastReplyKey: String?
    static var lastCallKey: String?

    static var extra: [String: () -> Void] = [:]
    static var answer: ((String) -> Void)?

    static func install(decline: @escaping (String) -> Void, openMessages: @escaping () -> Void,
                        reply: @escaping (String, String) -> Void) {
        guard let dir = ProcessInfo.processInfo.environment["PIXELBRIDGE_DEBUG"] else { return }
        DistributedNotificationCenter.default().addObserver(forName: .init("app.pixelbridge.debug"), object: nil, queue: .main) { n in
            let cmd = n.object as? String ?? ""
            if cmd == "snapshot" { snapshot(to: dir) }
            else if cmd == "decline", let k = lastCallKey { decline(k) }
            else if cmd == "answer", let k = lastCallKey { answer?(k) }
            else if cmd == "messages" { openMessages() }
            else if let f = extra[cmd] { f() }
            else if cmd.hasPrefix("reply:"), let k = lastReplyKey { reply(k, String(cmd.dropFirst(6))) }
        }
    }

    static func snapshot(to dir: String) {
        for (i, w) in NSApp.windows.enumerated() where w.isVisible {
            // Snapshots can't capture translucent backgrounds, so render in light mode for legibility.
            let saved = w.appearance
            w.appearance = NSAppearance(named: .aqua)
            w.displayIfNeeded()
            defer { w.appearance = saved }
            guard let v = w.contentView, v.bounds.width > 40, v.bounds.height > 40,
                  let rep = v.bitmapImageRepForCachingDisplay(in: v.bounds) else { continue }
            v.cacheDisplay(in: v.bounds, to: rep)
            let name = (w.title.isEmpty ? "window\(i)" : w.title).replacingOccurrences(of: " ", with: "_")
            try? rep.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: "\(dir)/\(name).png"))
        }
    }
}
