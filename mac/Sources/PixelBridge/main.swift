import AppKit
import BridgeCore
import ServiceManagement
import UserNotifications

final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private var statusItem: NSStatusItem!
    private var statusLine = NSMenuItem(title: "Starting…", action: nil, keyEquivalent: "")
    private var autoSyncItem: NSMenuItem!
    private var loginItem: NSMenuItem!
    private var sendClipItem: NSMenuItem!
    private var sendFilesItem: NSMenuItem!
    private var pairingWindow: PairingWindowController?
    private var notifItem: NSMenuItem!
    private var ringItem: NSMenuItem!
    private lazy var phoneEvents = PhoneEvents(server: server, canNotify: canNotify)
    private let adb = AdbTool()
    private lazy var setupModel = PhoneSetupModel(adb: adb)
    private var setupWindow: PhoneSetupWindowController?
    private var messagesWindow: MessagesWindowController?

    private var secret = SecretStore.load()
    private lazy var server = BridgeServer(secretProvider: { [weak self] in self?.secret ?? Data() })
    private let clipboard = ClipboardWatcher()
    private var connected = false

    private var autoSync: Bool {
        get { UserDefaults.standard.object(forKey: "autoSync") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "autoSync") }
    }

    private var canNotify: Bool { Bundle.main.bundleIdentifier != nil }

    func applicationDidFinishLaunching(_ notification: Notification) {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        setIcon(connected: false)
        statusItem.button?.toolTip = "Link to Mac"
        if let button = statusItem.button {
            let drop = DropView(frame: button.bounds)
            drop.autoresizingMask = [.width, .height]
            drop.onDrop = { [weak self] urls in self?.sendFiles(urls) }
            button.addSubview(drop)
        }
        buildMenu()

        _ = phoneEvents // registers notification categories (Reply / Decline)
        DebugHooks.install(decline: { [weak self] k in self?.phoneEvents.decline(key: k) },
                           openMessages: { [weak self] in self?.openMessages() },
                           reply: { [weak self] k, t in self?.phoneEvents.sendReply(key: k, text: t) })
        DebugHooks.answer = { [weak self] k in self?.phoneEvents.answer(key: k) }
        DebugHooks.extra = ["setup": { [weak self] in self?.showSetup() },
                            "screen": { [weak self] in self?.showPhoneScreen() }]
        server.onStateChange = { [weak self] st in self?.update(state: st) }
        server.onMessage = { [weak self] m in self?.handle(m) }
        server.onFileReceived = { [weak self] url in self?.fileReceived(url) }
        server.start()

        clipboard.onLocalCopy = { [weak self] content in
            guard let self, self.autoSync, self.connected else { return }
            self.push(content)
        }
        clipboard.start()

        NSApp.servicesProvider = self
        NSUpdateDynamicServices()

        if canNotify {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        }
        if SecretStore.isNew { showPairing() }
    }

    // MARK: Menu

    private func buildMenu() {
        let menu = NSMenu()
        menu.delegate = self
        statusLine.isEnabled = false
        menu.addItem(statusLine)
        menu.addItem(.separator())

        sendClipItem = menu.addItem(withTitle: "Send Clipboard to Phone", action: #selector(sendClipboardNow), keyEquivalent: "")
        sendFilesItem = menu.addItem(withTitle: "Send Files to Phone…", action: #selector(chooseFiles), keyEquivalent: "")
        autoSyncItem = menu.addItem(withTitle: "Sync Clipboard Automatically", action: #selector(toggleAutoSync), keyEquivalent: "")
        menu.addItem(.separator())
        menu.addItem(withTitle: "Phone Screen", action: #selector(showPhoneScreen), keyEquivalent: "")
        menu.addItem(withTitle: "Messages", action: #selector(openMessages), keyEquivalent: "")
        notifItem = menu.addItem(withTitle: "Show Phone Notifications", action: #selector(toggleNotifications), keyEquivalent: "")
        ringItem = menu.addItem(withTitle: "Ring for Phone Calls", action: #selector(toggleRing), keyEquivalent: "")
        menu.addItem(withTitle: "Set Up Phone…", action: #selector(showSetup), keyEquivalent: "")
        menu.addItem(.separator())
        menu.addItem(withTitle: "Pair Phone…", action: #selector(showPairing), keyEquivalent: "")
        menu.addItem(withTitle: "Reset Pairing Code…", action: #selector(resetPairing), keyEquivalent: "")
        menu.addItem(withTitle: "Open Downloads Folder", action: #selector(openDownloads), keyEquivalent: "")
        loginItem = menu.addItem(withTitle: "Launch at Login", action: #selector(toggleLogin), keyEquivalent: "")
        menu.addItem(.separator())
        menu.addItem(withTitle: "Quit Link to Mac", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        menu.items.forEach { if $0.action != nil && $0.target == nil && $0.action != #selector(NSApplication.terminate(_:)) { $0.target = self } }
        statusItem.menu = menu
    }

    func menuWillOpen(_ menu: NSMenu) {
        autoSyncItem.state = autoSync ? .on : .off
        notifItem.state = phoneEvents.mirrorNotifications ? .on : .off
        ringItem.state = phoneEvents.ringForCalls ? .on : .off
        loginItem.state = SMAppService.mainApp.status == .enabled ? .on : .off
        sendClipItem.isEnabled = connected
        sendFilesItem.isEnabled = connected
    }

    private func setIcon(connected: Bool) {
        let name = connected ? "iphone.radiowaves.left.and.right" : "iphone.slash"
        let img = NSImage(systemSymbolName: name, accessibilityDescription: "Link to Mac")
        img?.isTemplate = true
        statusItem.button?.image = img
    }

    private func update(state: BridgeServer.State) {
        switch state {
        case .starting:
            statusLine.title = "Starting…"; connected = false
        case .waiting:
            statusLine.title = "Waiting for phone…"; connected = false
        case .connected(let name):
            statusLine.title = "Connected to \(name)"; connected = true
        case .failed(let msg):
            statusLine.title = "Error: \(msg)"; connected = false
        }
        setIcon(connected: connected)
    }

    // MARK: Actions

    @objc private func toggleAutoSync() { autoSync.toggle() }
    @objc private func toggleNotifications() { phoneEvents.mirrorNotifications.toggle() }
    @objc private func toggleRing() { phoneEvents.ringForCalls.toggle() }

    @objc private func showSetup() {
        if setupWindow == nil { setupWindow = PhoneSetupWindowController(model: setupModel) }
        NSApp.activate(ignoringOtherApps: true)
        setupWindow?.showWindow(nil)
        setupModel.check()
    }

    @objc private func openMessages() {
        if messagesWindow == nil { messagesWindow = MessagesWindowController(model: phoneEvents.messages) }
        NSApp.activate(ignoringOtherApps: true)
        messagesWindow?.showWindow(nil)
        phoneEvents.messages.refresh()
    }

    /// Opens the phone's screen if adb can reach it; otherwise walks through setup.
    @objc private func showPhoneScreen() {
        DispatchQueue.global().async { [weak self] in
            guard let self else { return }
            if let d = self.adb.phone() {
                do { try self.adb.showScreen(d) } catch {
                    DispatchQueue.main.async { self.notify("Couldn't open Phone Screen", error.localizedDescription) }
                }
            } else {
                DispatchQueue.main.async { self.showSetup() }
            }
        }
    }

    @objc private func sendClipboardNow() {
        guard let c = clipboard.currentContent() else { return }
        push(c)
    }

    private func push(_ content: ClipboardWatcher.Content) {
        switch content {
        case .text(let s): server.send(Message("clip_text", body: Data(s.utf8)))
        case .image(let png):
            guard png.count < 6 * 1024 * 1024 else { return } // keep under one frame
            server.send(Message("clip_image", ["mime": "image/png"], body: png))
        }
    }

    @objc private func chooseFiles() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.prompt = "Send"
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK { sendFiles(panel.urls) }
    }

    func sendFiles(_ urls: [URL]) {
        let files = urls.filter { !$0.hasDirectoryPath }
        guard !files.isEmpty else { return }
        guard connected else { notify("Phone not connected", "Open Link to Mac on your phone and try again."); return }
        statusItem.button?.appearsDisabled = true
        server.sendFiles(files, progress: { [weak self] name, p in
            self?.statusLine.title = "Sending \(name)… \(Int(p * 100))%"
        }, completion: { [weak self] ok in
            guard let self else { return }
            self.statusItem.button?.appearsDisabled = false
            self.update(state: self.server.state)
            self.notify(ok ? "Sent to phone" : "Send failed",
                        ok ? (files.count == 1 ? files[0].lastPathComponent : "\(files.count) files") : "The connection dropped.")
        })
    }

    @objc private func showPairing() {
        let host = NetworkInfo.lanIPv4()
        let name = Host.current().localizedName ?? "Mac"
        let uri = Pairing.uri(secret: secret, host: host, port: server.port, name: name)
        pairingWindow?.close()
        pairingWindow = PairingWindowController(uri: uri, code: Pairing.code(for: secret),
                                                address: "\(host ?? "unknown"):\(server.port)")
        NSApp.activate(ignoringOtherApps: true)
        pairingWindow?.showWindow(nil)
        pairingWindow?.window?.makeKeyAndOrderFront(nil)
    }

    @objc private func resetPairing() {
        let alert = NSAlert()
        alert.messageText = "Reset pairing code?"
        alert.informativeText = "Your phone will be disconnected and must be paired again with the new code."
        alert.addButton(withTitle: "Reset")
        alert.addButton(withTitle: "Cancel")
        NSApp.activate(ignoringOtherApps: true)
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        secret = SecretStore.reset()
        server.disconnectAll()
        showPairing()
    }

    @objc private func openDownloads() {
        NSWorkspace.shared.open(FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0])
    }

    @objc private func toggleLogin() {
        do {
            if SMAppService.mainApp.status == .enabled { try SMAppService.mainApp.unregister() }
            else { try SMAppService.mainApp.register() }
        } catch {
            notify("Couldn't change login item", "Move Link to Mac to /Applications and try again.")
        }
    }

    // MARK: Incoming

    private func handle(_ m: Message) {
        switch m.type {
        case "clip_text":
            guard let s = String(data: m.body, encoding: .utf8) else { return }
            clipboard.setFromRemote(.text(s))
            notify("Copied from phone", String(s.prefix(120)))
        case "clip_image":
            clipboard.setFromRemote(.image(pngData(m.body) ?? m.body))
            notify("Image copied from phone", "Paste it anywhere with ⌘V")
        default:
            _ = phoneEvents.handle(m)
        }
    }

    /// Phones may send JPEG/WebP; normalise to PNG so every Mac app can paste it.
    private func pngData(_ d: Data) -> Data? {
        guard let rep = NSBitmapImageRep(data: d) else { return nil }
        return rep.representation(using: .png, properties: [:])
    }

    private func fileReceived(_ url: URL) {
        notify("Received \(url.lastPathComponent)", "Saved to Downloads", reveal: url)
    }

    private func notify(_ title: String, _ body: String, reveal: URL? = nil) {
        guard canNotify else { NSLog("[PixelBridge] %@: %@", title, body); return }
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        if let reveal { c.userInfo = ["reveal": reveal.path] }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil))
    }

    // MARK: Services menu ("Send to Phone with PixelBridge" in Finder / text right-click)

    @objc func sendToPhone(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        if let urls = pboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !urls.isEmpty {
            sendFiles(urls)
        } else if let s = pboard.string(forType: .string) {
            guard connected else { error.pointee = "Phone not connected"; return }
            server.send(Message("clip_text", body: Data(s.utf8)))
            notify("Sent to phone clipboard", String(s.prefix(120)))
        }
    }
}

extension AppDelegate: UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        if let path = info["reveal"] as? String {
            NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
        } else {
            _ = phoneEvents.respond(actionIdentifier: response.actionIdentifier, userInfo: info,
                                    text: (response as? UNTextInputNotificationResponse)?.userText)
        }
        completionHandler()
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner])
    }
}

/// Transparent view over the menu bar button so files can be dropped on the icon.
final class DropView: NSView {
    var onDrop: (([URL]) -> Void)?

    override init(frame: NSRect) {
        super.init(frame: frame)
        registerForDraggedTypes([.fileURL])
    }

    required init?(coder: NSCoder) { fatalError() }

    override func hitTest(_ point: NSPoint) -> NSView? { nil } // let clicks reach the button

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation { .copy }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        let urls = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self],
                                                         options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        onDrop?(urls)
        return !urls.isEmpty
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
if Bundle.main.bundleIdentifier != nil {
    UNUserNotificationCenter.current().delegate = delegate
}
app.run()
