import AppKit
import BridgeCore

/// Sends a small copy of the Mac's desktop wallpaper to connected phones, so the laptop drawn in the
/// phone app shows the real desktop. Sent when a phone connects and whenever the wallpaper changes.
final class WallpaperSync {
    private let server: BridgeServer
    private var lastSent: String?
    private var timer: Timer?

    init(server: BridgeServer) { self.server = server }

    func start() {
        // macOS has no "wallpaper changed" notification; a cheap check every 20 s (and on Space switches) is enough.
        let t = Timer(timeInterval: 20, repeats: true) { [weak self] _ in self?.check() }
        RunLoop.main.add(t, forMode: .common)
        timer = t
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.activeSpaceDidChangeNotification,
                                                          object: nil, queue: .main) { [weak self] _ in self?.check() }
    }

    /// Call when a phone connects: always send, even if unchanged.
    func sendNow() {
        lastSent = nil
        check()
    }

    private func check() {
        guard server.hasPhone, let screen = NSScreen.main,
              let url = NSWorkspace.shared.desktopImageURL(for: screen) else { return }
        let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate)?
            .timeIntervalSince1970 ?? 0
        let key = "\(url.path)|\(modified)"
        guard key != lastSent else { return }
        lastSent = key
        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let jpeg = Self.render(url) else { pbLog("Couldn't read wallpaper at \(url.lastPathComponent)"); return }
            self?.server.send(Message("wallpaper", ["w": 800, "h": 500], body: jpeg))
            pbLog("Sent wallpaper (\(jpeg.count / 1024) KB)")
        }
    }

    /// Aerial/dynamic wallpapers are .madesktop bundles; macOS keeps a still thumbnail for each.
    private static func image(at url: URL) -> NSImage? {
        if url.pathExtension != "madesktop", !url.hasDirectoryPath, let img = NSImage(contentsOf: url) { return img }
        let name = url.deletingPathExtension().lastPathComponent
        for dir in ["/System/Library/Desktop Pictures/.thumbnails", "/Library/Desktop Pictures/.thumbnails"] {
            for ext in ["heic", "png", "jpg"] {
                if let img = NSImage(contentsOfFile: "\(dir)/\(name).\(ext)") { return img }
            }
        }
        return nil
    }

    /// 800×500 (the phone's laptop screen is 16:10), cropped to fill, as JPEG.
    private static func render(_ url: URL) -> Data? {
        guard let img = image(at: url), img.size.width > 0, img.size.height > 0 else { return nil }
        let target = NSSize(width: 800, height: 500)
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(target.width), pixelsHigh: Int(target.height),
                                   bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                   colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        NSGraphicsContext.current?.imageInterpolation = .high
        let scale = max(target.width / img.size.width, target.height / img.size.height)
        let w = img.size.width * scale, h = img.size.height * scale
        img.draw(in: NSRect(x: (target.width - w) / 2, y: (target.height - h) / 2, width: w, height: h))
        NSGraphicsContext.restoreGraphicsState()
        return rep.representation(using: .jpeg, properties: [.compressionFactor: 0.82])
    }
}
