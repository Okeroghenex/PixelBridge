import AVFoundation
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

    /// What's on the desktop: an aerial (video), a picture file, or whatever NSWorkspace reports.
    private enum Source { case aerial(id: String), file(URL) }

    private static let store = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Application Support/com.apple.wallpaper")

    /// Since macOS 14, wallpapers picked in System Settings live in the wallpaper store, and
    /// NSWorkspace.desktopImageURL can report a stale default (e.g. for Aerials). Read the store first.
    private static func currentSource() -> Source? {
        let index = store.appendingPathComponent("Store/Index.plist")
        if let data = try? Data(contentsOf: index),
           let root = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any] {
            for scope in ["AllSpacesAndDisplays", "SystemDefault"] {
                guard let entry = root[scope] as? [String: Any] else { continue }
                // "linked": one wallpaper for desktop and screen saver; "individual": separate ones.
                let side = (entry["Linked"] ?? entry["Desktop"]) as? [String: Any]
                guard let choice = ((side?["Content"] as? [String: Any])?["Choices"] as? [[String: Any]])?.first
                else { continue }
                let provider = choice["Provider"] as? String ?? ""
                if provider.hasSuffix(".aerials"), let cfg = choice["Configuration"] as? Data,
                   let dict = try? PropertyListSerialization.propertyList(from: cfg, format: nil) as? [String: Any],
                   let id = dict["assetID"] as? String {
                    return .aerial(id: id)
                }
                if let files = choice["Files"] as? [[String: Any]], let rel = files.first?["relative"] as? String,
                   let url = URL(string: rel), url.isFileURL {
                    return .file(url)
                }
            }
        }
        guard let screen = NSScreen.main, let url = NSWorkspace.shared.desktopImageURL(for: screen) else { return nil }
        return .file(url)
    }

    private func check() {
        guard server.hasPhone, let source = Self.currentSource() else { return }
        let key: String
        switch source {
        case .aerial(let id): key = "aerial|\(id)"
        case .file(let url):
            let modified = (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate)?
                .timeIntervalSince1970 ?? 0
            key = "\(url.path)|\(modified)"
        }
        guard key != lastSent else { return }
        lastSent = key
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let image: NSImage?
            switch source {
            case .aerial(let id): image = Self.aerialFrame(id)
            case .file(let url): image = Self.image(at: url)
            }
            guard let image, let jpeg = Self.render(image) else { pbLog("Couldn't read the wallpaper (\(key))"); return }
            self?.server.send(Message("wallpaper", ["w": 800, "h": 500], body: jpeg))
            pbLog("Sent wallpaper (\(jpeg.count / 1024) KB)")
        }
    }

    /// A still from an Aerial's downloaded video (its bundled thumbnail is only ~214 px wide).
    private static func aerialFrame(_ id: String) -> NSImage? {
        let video = store.appendingPathComponent("aerials/videos/\(id).mov")
        if FileManager.default.fileExists(atPath: video.path) {
            let gen = AVAssetImageGenerator(asset: AVURLAsset(url: video))
            gen.appliesPreferredTrackTransform = true
            gen.maximumSize = CGSize(width: 1600, height: 1000)
            if let cg = try? gen.copyCGImage(at: .zero, actualTime: nil) {
                return NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
            }
        }
        return NSImage(contentsOf: store.appendingPathComponent("aerials/thumbnails/\(id).png"))
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
    private static func render(_ img: NSImage) -> Data? {
        guard img.size.width > 0, img.size.height > 0 else { return nil }
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
