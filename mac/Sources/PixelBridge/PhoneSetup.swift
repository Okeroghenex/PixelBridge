import AppKit
import SwiftUI

/// Wraps the adb + scrcpy binaries bundled in PixelBridge.app/Contents/Resources/scrcpy.
/// adb (over USB or Wi-Fi "Wireless debugging") is used for the one-time grants behind automatic
/// clipboard, and scrcpy shows and controls the phone's screen.
final class AdbTool {
    static let package = "app.pixelbridge.android"
    let dir: URL?

    init() {
        let d = Bundle.main.resourceURL?.appendingPathComponent("scrcpy")
        dir = d.flatMap { FileManager.default.isExecutableFile(atPath: $0.appendingPathComponent("adb").path) ? $0 : nil }
    }

    var available: Bool { dir != nil }
    private var adb: String { dir!.appendingPathComponent("adb").path }

    @discardableResult
    func run(_ args: [String], timeout: TimeInterval = 20) -> (code: Int32, out: String) {
        guard available else { return (-1, "adb missing") }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: adb)
        p.arguments = args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        do { try p.run() } catch { return (-1, "\(error)") }
        let deadline = Date().addingTimeInterval(timeout)
        while p.isRunning && Date() < deadline { Thread.sleep(forTimeInterval: 0.05) }
        if p.isRunning { p.terminate() }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        return (p.terminationStatus, String(decoding: data, as: UTF8.self))
    }

    struct Device { let serial: String; let model: String }

    func devices() -> [Device] {
        run(["devices", "-l"]).out.split(separator: "\n").dropFirst().compactMap { line in
            let parts = line.split(separator: " ", omittingEmptySubsequences: true)
            guard parts.count >= 2, parts[1] == "device" else { return nil }
            let model = parts.first { $0.hasPrefix("model:") }.map { $0.dropFirst(6).replacingOccurrences(of: "_", with: " ") }
            return Device(serial: String(parts[0]), model: model ?? String(parts[0]))
        }
    }

    /// Connects to phones advertising Wireless debugging that this Mac has paired with before.
    func connectDiscovered() {
        for addr in BonjourLookup.find(type: "_adb-tls-connect._tcp.", timeout: 2) { run(["connect", addr], timeout: 8) }
        for line in run(["mdns", "services"], timeout: 5).out.split(separator: "\n") where line.contains("_adb-tls-connect") {
            if let addr = line.split(whereSeparator: { $0 == "\t" || $0 == " " }).last(where: { $0.contains(":") }) {
                run(["connect", String(addr)], timeout: 8)
            }
        }
    }

    /// The connected phone that has PixelBridge installed.
    func phone() -> Device? {
        let all = devices()
        if all.isEmpty { connectDiscovered() }
        let list = all.isEmpty ? devices() : all
        return list.first { run(["-s", $0.serial, "shell", "pm", "path", Self.package], timeout: 8).out.contains("package:") }
            ?? list.first
    }

    /// After the phone scans our QR code it advertises `_adb-tls-pairing._tcp` under the name we chose.
    func pairingAddress(named name: String) -> String? {
        if let addr = BonjourLookup.find(type: "_adb-tls-pairing._tcp.", timeout: 1.5, match: { $0 == name }).first {
            return addr
        }
        for line in run(["mdns", "services"], timeout: 5).out.split(separator: "\n")
        where line.contains("_adb-tls-pairing") && line.contains(name) {
            if let addr = line.split(whereSeparator: { $0 == "\t" || $0 == " " }).last(where: { $0.contains(":") }) {
                return String(addr)
            }
        }
        return nil
    }

    func pair(address: String, code: String) -> (ok: Bool, message: String) {
        let r = run(["pair", address, code], timeout: 25)
        let ok = r.out.localizedCaseInsensitiveContains("successfully paired")
        return (ok, r.out.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    func enableAutoClipboard(_ d: Device) -> Bool {
        let a = run(["-s", d.serial, "shell", "pm", "grant", Self.package, "android.permission.READ_LOGS"])
        let b = run(["-s", d.serial, "shell", "appops", "set", Self.package, "SYSTEM_ALERT_WINDOW", "allow"])
        // Bring the app to the front so Android can ask "Allow access to device logs?".
        run(["-s", d.serial, "shell", "am", "start", "-n", "\(Self.package)/.MainActivity"])
        return a.code == 0 && b.code == 0 && autoClipboardEnabled(d)
    }

    /// Both grants are needed: READ_LOGS to notice copies and "display over other apps" to read them.
    /// Reinstalling the phone app clears them, so check both rather than assume.
    func autoClipboardEnabled(_ d: Device) -> Bool {
        let logs = run(["-s", d.serial, "shell", "dumpsys", "package", Self.package], timeout: 10).out
            .contains("android.permission.READ_LOGS: granted=true")
        let overlay = run(["-s", d.serial, "shell", "appops", "get", Self.package, "SYSTEM_ALERT_WINDOW"], timeout: 10).out
            .contains("SYSTEM_ALERT_WINDOW: allow")
        return logs && overlay
    }

    private var mirror: Process?

    func showScreen(_ d: Device) throws {
        if let m = mirror, m.isRunning { return }
        guard let dir else { return }
        let p = Process()
        p.executableURL = dir.appendingPathComponent("scrcpy")
        p.arguments = ["-s", d.serial, "--window-title", d.model, "--stay-awake", "--shortcut-mod", "lsuper"]
        var env = ProcessInfo.processInfo.environment
        env["ADB"] = adb
        env["SCRCPY_SERVER_PATH"] = dir.appendingPathComponent("scrcpy-server").path
        p.environment = env
        let logURL = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/PixelBridge-scrcpy.log")
        FileManager.default.createFile(atPath: logURL.path, contents: nil)
        if let h = try? FileHandle(forWritingTo: logURL) { p.standardOutput = h; p.standardError = h }
        try p.run()
        mirror = p
    }
}

/// Finds Wireless-debugging services with macOS's own Bonjour. adb's built-in discovery can go stale
/// (a long-running adb server stops seeing new phones), so we look them up ourselves and hand adb an address.
final class BonjourLookup: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    private let browser = NetServiceBrowser()
    private let match: (String) -> Bool
    private var resolving: [NetService] = []
    private var results: [String] = []
    private let found = DispatchSemaphore(value: 0)

    private init(match: @escaping (String) -> Bool) { self.match = match }

    /// "ip:port" of each matching service. Call from a background queue (it waits for the main run loop).
    static func find(type: String, timeout: TimeInterval, match: @escaping (String) -> Bool = { _ in true }) -> [String] {
        precondition(!Thread.isMainThread)
        let l = BonjourLookup(match: match)
        DispatchQueue.main.async {
            l.browser.delegate = l
            l.browser.searchForServices(ofType: type, inDomain: "local.")
        }
        _ = l.found.wait(timeout: .now() + timeout)
        return DispatchQueue.main.sync {
            l.browser.stop()
            l.resolving.forEach { $0.stop() }
            return l.results
        }
    }

    func netServiceBrowser(_ b: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        guard match(service.name) else { return }
        resolving.append(service)
        service.delegate = self
        service.resolve(withTimeout: 5)
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        for data in sender.addresses ?? [] {
            let ip: String? = data.withUnsafeBytes { raw in
                guard let sa = raw.baseAddress?.assumingMemoryBound(to: sockaddr.self),
                      sa.pointee.sa_family == sa_family_t(AF_INET) else { return nil }
                var sin = raw.load(as: sockaddr_in.self)
                var buf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                inet_ntop(AF_INET, &sin.sin_addr, &buf, socklen_t(INET_ADDRSTRLEN))
                return String(cString: buf)
            }
            if let ip, !results.contains("\(ip):\(sender.port)") {
                results.append("\(ip):\(sender.port)")
                found.signal()
            }
        }
    }
}

// MARK: - Setup window

final class PhoneSetupModel: ObservableObject {
    @Published var device: String?
    @Published var checking = false
    @Published var autoClipOn = false
    @Published var message: String?
    @Published var address = ""
    @Published var code = ""
    enum PairMethod: Hashable { case qr, code }
    @Published var method: PairMethod = .qr {
        didSet { if method == .qr { startQR() } else { stopQR() } }
    }
    @Published var qrImage: NSImage?
    @Published var qrStatus = ""
    private var qrSession = 0
    let adb: AdbTool
    private let q = DispatchQueue(label: "pixelbridge.adb")

    init(adb: AdbTool) { self.adb = adb }

    func check() {
        guard adb.available else { message = "This build doesn't include the phone tools. Rebuild with build-app.sh."; return }
        checking = true
        q.async {
            let d = self.adb.phone()
            let on = d.map { self.adb.autoClipboardEnabled($0) } ?? false
            DispatchQueue.main.async {
                self.checking = false
                self.device = d?.model
                self.autoClipOn = on
                if d == nil, self.method == .qr { self.startQR() } else if d != nil { self.stopQR() }
            }
        }
    }

    func pair() {
        let addr = address.trimmingCharacters(in: .whitespaces), c = code.trimmingCharacters(in: .whitespaces)
        guard !addr.isEmpty, !c.isEmpty else { return }
        checking = true
        message = nil
        q.async {
            let r = self.adb.pair(address: addr, code: c)
            DispatchQueue.main.async {
                self.message = r.ok ? "Paired. Connecting…" : "Pairing failed: \(r.message)"
                if r.ok { self.code = "" }
            }
            Thread.sleep(forTimeInterval: 2)
            DispatchQueue.main.async { self.check() }
        }
    }

    // MARK: QR code pairing (Wireless debugging › Pair device with QR code)

    /// Shows a fresh QR code and waits up to 3 minutes for the phone to scan it.
    func startQR() {
        guard adb.available, device == nil else { return }
        if qrImage != nil, qrStatus.hasPrefix("Waiting") { return } // already waiting
        qrSession += 1
        let session = qrSession
        let alphabet = Array("abcdefghijkmnpqrstuvwxyz23456789")
        let name = "linktomac-" + String((0..<6).map { _ in alphabet.randomElement()! })
        let password = String((0..<10).map { _ in alphabet.randomElement()! })
        qrImage = QRCode.image(for: "WIFI:T:ADB;S:\(name);P:\(password);;", size: 190)
        qrStatus = "Waiting for your phone to scan the code…"
        q.async {
            let deadline = Date().addingTimeInterval(180)
            while Date() < deadline {
                guard self.qrSession == session else { return }
                if let addr = self.adb.pairingAddress(named: name) {
                    DispatchQueue.main.async { if self.qrSession == session { self.qrStatus = "Pairing…" } }
                    let r = self.adb.pair(address: addr, code: password)
                    guard self.qrSession == session else { return }
                    if r.ok {
                        DispatchQueue.main.async { self.qrStatus = "Paired. Connecting…" }
                        // The phone advertises its connect service a moment after pairing.
                        for _ in 0..<15 {
                            self.adb.connectDiscovered()
                            if !self.adb.devices().isEmpty { break }
                            Thread.sleep(forTimeInterval: 1)
                        }
                        DispatchQueue.main.async {
                            self.qrImage = nil
                            self.qrStatus = ""
                            self.check()
                        }
                    } else {
                        DispatchQueue.main.async {
                            self.qrImage = nil
                            self.qrStatus = "Pairing failed: \(r.message)"
                        }
                    }
                    return
                }
                Thread.sleep(forTimeInterval: 1)
            }
            DispatchQueue.main.async {
                guard self.qrSession == session else { return }
                self.qrImage = nil
                self.qrStatus = "The code expired."
            }
        }
    }

    func stopQR() {
        qrSession += 1
        qrImage = nil
        qrStatus = ""
    }

    func enableAutoClipboard() {
        checking = true
        q.async {
            guard let d = self.adb.phone() else { DispatchQueue.main.async { self.checking = false }; return }
            let ok = self.adb.enableAutoClipboard(d)
            DispatchQueue.main.async {
                self.checking = false
                self.autoClipOn = ok
                self.message = ok ? "Done. On your phone, tap “Allow one-time access”." : "Couldn't change the setting on the phone."
            }
        }
    }

    func showScreen() {
        q.async {
            guard let d = self.adb.phone() else {
                DispatchQueue.main.async { self.message = "Phone not found. Pair it first." }
                return
            }
            do { try self.adb.showScreen(d) } catch {
                DispatchQueue.main.async { self.message = "Couldn't open the phone screen: \(error.localizedDescription)" }
            }
        }
    }
}

final class PhoneSetupWindowController: NSWindowController {
    convenience init(model: PhoneSetupModel) {
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 520, height: 600),
                              styleMask: [.titled, .closable], backing: .buffered, defer: false)
        window.title = "Set Up Phone"
        window.isReleasedWhenClosed = false
        window.contentView = NSHostingView(rootView: PhoneSetupView(model: model))
        window.center()
        self.init(window: window)
    }
}

struct PhoneSetupView: View {
    @ObservedObject var model: PhoneSetupModel

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 12) {
                Image(systemName: model.device == nil ? "iphone.slash" : "iphone.radiowaves.left.and.right")
                    .font(.system(size: 26)).foregroundStyle(model.device == nil ? Color.secondary : Color.green)
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.device ?? "Phone not linked yet").font(.title3.weight(.semibold))
                    Text(model.device == nil
                         ? "Link once to turn on automatic clipboard and Phone Screen."
                         : "Linked for automatic clipboard and Phone Screen.")
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if model.checking { ProgressView().controlSize(.small) }
                else { Button("Check Again", action: model.check) }
            }

            if model.device == nil {
                Picker("", selection: $model.method) {
                    Text("Pair with QR code").tag(PhoneSetupModel.PairMethod.qr)
                    Text("Pair with pairing code").tag(PhoneSetupModel.PairMethod.code)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                GroupBox {
                    if model.method == .qr { qrSteps } else { codeSteps }
                }
            } else {
                GroupBox {
                    VStack(spacing: 0) {
                        FeatureRow(symbol: "doc.on.clipboard", color: .blue, title: "Copy on phone, paste on Mac",
                                   detail: model.autoClipOn
                                   ? "On. After the phone restarts, tap Link to Mac's notification once."
                                   : "Sends anything you copy on the phone to this Mac.") {
                            if model.autoClipOn { Label("On", systemImage: "checkmark.circle.fill").foregroundStyle(.green) }
                            else { Button("Turn On", action: model.enableAutoClipboard) }
                        }
                        Divider().padding(.vertical, 8)
                        FeatureRow(symbol: "rectangle.on.rectangle", color: .purple, title: "Phone Screen",
                                   detail: "See and control your phone in a window on your Mac.") {
                            Button("Open", action: model.showScreen)
                        }
                    }
                    .padding(8)
                }
            }

            if let m = model.message {
                Text(m).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
            }
            Spacer()
            Text("Wireless debugging turns off when the phone leaves this Wi-Fi network. Turn it back on to use Phone Screen again; automatic clipboard keeps working without it.")
                .font(.footnote).foregroundStyle(.tertiary)
        }
        .padding(24)
        .frame(width: 520, height: 600)
        .onAppear(perform: model.check)
    }

    private var enableDebugging: some View {
        Step(n: 1, text: "On your phone open **Settings › System › Developer options** and turn on **Wireless debugging**. (No Developer options? Settings › About phone › tap **Build number** 7 times.)")
    }

    private var qrSteps: some View {
        VStack(alignment: .leading, spacing: 10) {
            enableDebugging
            Step(n: 2, text: "Tap **Wireless debugging › Pair device with QR code** and scan this code:")
            HStack(alignment: .center, spacing: 16) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10).fill(Color.white)
                    if let img = model.qrImage {
                        Image(nsImage: img).interpolation(.none).resizable().frame(width: 190, height: 190)
                    } else {
                        Button("Show New Code", action: model.startQR)
                    }
                }
                .frame(width: 206, height: 206)
                VStack(alignment: .leading, spacing: 8) {
                    if model.qrStatus.hasPrefix("Waiting") || model.qrStatus.hasSuffix("…") {
                        ProgressView().controlSize(.small)
                    }
                    Text(model.qrStatus).font(.callout).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("Your phone and Mac must be on the same Wi-Fi.")
                        .font(.callout).foregroundStyle(.tertiary).fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.leading, 30)
        }
        .padding(8)
    }

    private var codeSteps: some View {
        VStack(alignment: .leading, spacing: 10) {
            enableDebugging
            Step(n: 2, text: "Tap **Wireless debugging › Pair device with pairing code**.")
            Step(n: 3, text: "Type the **IP address & Port** and **Wi-Fi pairing code** it shows:")
            HStack {
                TextField("192.168.1.23:37099", text: $model.address).textFieldStyle(.roundedBorder)
                TextField("123456", text: $model.code).textFieldStyle(.roundedBorder).frame(width: 90)
                Button("Pair", action: model.pair).keyboardShortcut(.defaultAction)
                    .disabled(model.address.isEmpty || model.code.isEmpty)
            }
            .padding(.leading, 30)
            Text("Or plug the phone in with a USB cable and tap Allow on it.")
                .font(.callout).foregroundStyle(.secondary).padding(.leading, 30)
        }
        .padding(8)
    }
}

private struct Step: View {
    let n: Int
    let text: LocalizedStringKey
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(n)").font(.callout.weight(.bold)).foregroundStyle(.white)
                .frame(width: 20, height: 20).background(Circle().fill(Color.accentColor))
            Text(text).fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct FeatureRow<Trailing: View>: View {
    let symbol: String
    let color: Color
    let title: String
    let detail: String
    @ViewBuilder let trailing: () -> Trailing
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol).foregroundStyle(color).font(.system(size: 16, weight: .semibold))
                .frame(width: 34, height: 34).background(RoundedRectangle(cornerRadius: 9).fill(color.opacity(0.14)))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body.weight(.medium))
                Text(detail).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            trailing()
        }
    }
}
