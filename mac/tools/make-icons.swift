// Renders Link to Mac's branding SVGs into the Mac app's resources:
//   MenuIcon(.png|@2x.png)      menu bar template icon (glyph only)
//   MenuIconOff(.png|@2x.png)   same, faded, shown while no phone is connected
//   AppIcon.icns                Finder / notification icon (white glyph on a black macOS-shaped tile)
// Usage: swift tools/make-icons.swift <branding dir> <output dir>
import AppKit

let args = CommandLine.arguments
let branding = URL(fileURLWithPath: args[1]), out = URL(fileURLWithPath: args[2])
try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)

guard let glyph = NSImage(contentsOf: branding.appendingPathComponent("Link to Mac Icon Black.svg")) else {
    fatalError("can't load glyph SVG")
}

func png(_ size: NSSize, scale: CGFloat, _ draw: (NSRect) -> Void) -> Data {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width * scale), pixelsHigh: Int(size.height * scale),
                               bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = size
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    NSGraphicsContext.current?.imageInterpolation = .high
    draw(NSRect(origin: .zero, size: size))
    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])!
}

func fitted(_ box: NSRect, inset: CGFloat) -> NSRect {
    let r = box.insetBy(dx: inset, dy: inset)
    let k = min(r.width / glyph.size.width, r.height / glyph.size.height)
    let w = glyph.size.width * k, h = glyph.size.height * k
    return NSRect(x: r.midX - w / 2, y: r.midY - h / 2, width: w, height: h)
}

// Menu bar: 18 pt box like SF Symbols.
for (name, alpha) in [("MenuIcon", 1.0), ("MenuIconOff", 0.4)] {
    for scale in [1.0, 2.0] {
        let data = png(NSSize(width: 18, height: 18), scale: scale) { r in
            glyph.draw(in: fitted(r, inset: 1), from: .zero, operation: .sourceOver, fraction: alpha)
        }
        try! data.write(to: out.appendingPathComponent(scale == 1 ? "\(name).png" : "\(name)@2x.png"))
    }
}

// App icon: Apple's grid (824 pt rounded tile on a 1024 canvas), glyph at the same proportion as the brand file.
let whiteGlyph = NSImage(size: glyph.size, flipped: false) { r in
    glyph.draw(in: r)
    NSColor.white.set()
    r.fill(using: .sourceAtop)
    return true
}
let iconset = FileManager.default.temporaryDirectory.appendingPathComponent("AppIcon.iconset")
try? FileManager.default.removeItem(at: iconset)
try! FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)
for base in [16, 32, 128, 256, 512] {
    for scale in [1, 2] {
        let data = png(NSSize(width: base, height: base), scale: CGFloat(scale)) { r in
            let tile = r.insetBy(dx: r.width * 100 / 1024, dy: r.height * 100 / 1024)
            NSColor.black.setFill()
            NSBezierPath(roundedRect: tile, xRadius: tile.width * 0.225, yRadius: tile.width * 0.225).fill()
            // In the brand files the glyph spans ~150/272 of the tile width.
            whiteGlyph.draw(in: fitted(tile, inset: tile.width * (1 - 150.0 / 272.0) / 2))
        }
        try! data.write(to: iconset.appendingPathComponent(scale == 1 ? "icon_\(base)x\(base).png" : "icon_\(base)x\(base)@2x.png"))
    }
}
let p = Process()
p.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
p.arguments = ["-c", "icns", iconset.path, "-o", out.appendingPathComponent("AppIcon.icns").path]
try! p.run(); p.waitUntilExit()
print("icons written to \(out.path)")
