import AppKit
import BridgeCore
import SwiftUI
import UserNotifications

/// Handles everything the phone reports that isn't clipboard/files: notifications, calls, SMS.
final class PhoneEvents: NSObject {
    static let replyCategory = "pb.msg.reply"
    static let messageCategory = "pb.msg"
    static let callCategory = "pb.call"

    private let server: BridgeServer
    private let canNotify: Bool
    private var callPanel: IncomingCallPanel?
    private var ringer: Ringer?
    private var ringingKey: String?
    let messages: MessagesModel

    var mirrorNotifications: Bool {
        get { UserDefaults.standard.object(forKey: "mirrorNotifications") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "mirrorNotifications") }
    }

    var ringForCalls: Bool {
        get { UserDefaults.standard.object(forKey: "ringForCalls") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "ringForCalls") }
    }

    init(server: BridgeServer, canNotify: Bool) {
        self.server = server
        self.canNotify = canNotify
        self.messages = MessagesModel(server: server)
        super.init()
        if canNotify { registerCategories() }
    }

    private func registerCategories() {
        let reply = UNTextInputNotificationAction(identifier: "reply", title: "Reply", options: [],
                                                  textInputButtonTitle: "Send", textInputPlaceholder: "Message")
        let dismiss = UNNotificationAction(identifier: "dismiss", title: "Clear on Phone", options: [])
        let decline = UNNotificationAction(identifier: "decline", title: "Decline", options: [.destructive])
        UNUserNotificationCenter.current().setNotificationCategories([
            UNNotificationCategory(identifier: Self.replyCategory, actions: [reply, dismiss], intentIdentifiers: []),
            UNNotificationCategory(identifier: Self.messageCategory, actions: [dismiss], intentIdentifiers: []),
            UNNotificationCategory(identifier: Self.callCategory, actions: [decline], intentIdentifiers: []),
        ])
    }

    /// Returns true if the message was one of ours.
    func handle(_ m: Message) -> Bool {
        pbLog("event \(m.type) \(m.string("state") ?? "") from \(m.string("appName") ?? m.string("app") ?? "phone")")
        switch m.type {
        case "notif": showNotification(m)
        case "notif_removed":
            if let key = m.string("key") {
                UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [ident(key)])
            }
        case "notif_reply_result":
            if (m.header["ok"] as? Bool) == false { post(title: "Reply not sent", body: "The app on your phone didn't accept it.") }
        case "call": handleCall(m)
        case "sms_threads", "sms_messages": messages.handle(m)
        default: return false
        }
        return true
    }

    // MARK: Notifications

    private func ident(_ key: String) -> String { "notif:" + key }

    private func showNotification(_ m: Message) {
        guard canNotify, mirrorNotifications, let key = m.string("key") else { return }
        let c = UNMutableNotificationContent()
        let app = m.string("appName") ?? "Phone"
        let title = m.string("title") ?? ""
        c.title = title.isEmpty ? app : title
        c.subtitle = title.isEmpty ? "" : app
        c.body = m.string("text") ?? ""
        c.sound = .default
        c.threadIdentifier = m.string("app") ?? "phone"
        c.categoryIdentifier = (m.header["canReply"] as? Bool) == true ? Self.replyCategory : Self.messageCategory
        c.userInfo = ["key": key]
        if (m.header["canReply"] as? Bool) == true { DebugHooks.lastReplyKey = key }
        if let att = iconAttachment(m.body, name: m.string("app") ?? "app") { c.attachments = [att] }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: ident(key), content: c, trigger: nil))
    }

    private func iconAttachment(_ png: Data, name: String) -> UNNotificationAttachment? {
        guard !png.isEmpty else { return nil }
        // Attachments are moved into the notification store, so write a fresh copy each time.
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("pb-\(name)-\(UUID().uuidString).png")
        guard (try? png.write(to: url)) != nil else { return nil }
        return try? UNNotificationAttachment(identifier: "icon", url: url, options: nil)
    }

    /// From the notification delegate.
    func respond(actionIdentifier: String, userInfo: [AnyHashable: Any], text: String?) -> Bool {
        if let key = userInfo["key"] as? String {
            switch actionIdentifier {
            case "reply":
                if let text, !text.isEmpty { server.send(Message("notif_reply", ["key": key], body: Data(text.utf8))) }
            case "dismiss": server.send(Message("notif_dismiss", ["key": key]))
            default: break
            }
            return true
        }
        if let callKey = userInfo["callKey"] as? String {
            if actionIdentifier == "decline" { decline(key: callKey) }
            else if actionIdentifier == UNNotificationDefaultActionIdentifier { callPanel?.window?.orderFrontRegardless() }
            return true
        }
        return false
    }

    private func post(title: String, body: String) {
        guard canNotify else { return }
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil))
    }

    // MARK: Calls

    private func handleCall(_ m: Message) {
        guard let key = m.string("key") else { return }
        switch m.string("state") {
        case "ringing":
            guard ringForCalls else { return }
            ringingKey = key
            DebugHooks.lastCallKey = key
            let name = m.string("name") ?? "Unknown caller"
            let detail = m.string("detail") ?? ""
            let icon = NSImage(data: m.body)
            showCallPanel(key: key, name: name, detail: detail, icon: icon,
                          canDecline: (m.header["canDecline"] as? Bool) ?? false)
            if canNotify {
                let c = UNMutableNotificationContent()
                c.title = "\(name) is calling"
                c.body = detail.isEmpty ? "Answer on your phone" : detail
                c.categoryIdentifier = Self.callCategory
                c.userInfo = ["callKey": key]
                c.interruptionLevel = .timeSensitive
                UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "call:" + key, content: c, trigger: nil))
            }
        default: // "active" (picked up on the phone) or "ended"
            endRinging(key: key)
        }
    }

    private func showCallPanel(key: String, name: String, detail: String, icon: NSImage?, canDecline: Bool) {
        callPanel?.close()
        let panel = IncomingCallPanel(name: name, detail: detail, icon: icon, canDecline: canDecline,
                                      onDecline: { [weak self] in self?.decline(key: key) },
                                      onSilence: { [weak self] in self?.silence() })
        callPanel = panel
        panel.present()
        ringer = Ringer()
        ringer?.start()
    }

    private func silence() {
        ringer?.stop()
        ringer = nil
        callPanel?.close()
        callPanel = nil
    }

    func sendReply(key: String, text: String) {
        server.send(Message("notif_reply", ["key": key], body: Data(text.utf8)))
    }

    func decline(key: String) {
        server.send(Message("call_action", ["key": key, "action": "decline"]))
        endRinging(key: key)
    }

    private func endRinging(key: String) {
        guard key == ringingKey || ringingKey == nil else { return }
        ringingKey = nil
        silence()
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["call:" + key])
    }
}

/// Loops a system ringtone until stopped.
final class Ringer {
    private var sound: NSSound?
    private var timer: Timer?

    func start() {
        let ringtone = "/System/Library/PrivateFrameworks/ToneLibrary.framework/Versions/A/Resources/Ringtones/Reflection.m4r"
        if let s = NSSound(contentsOfFile: ringtone, byReference: true) {
            s.loops = true
            s.play()
            sound = s
        } else {
            let t = Timer(timeInterval: 1.6, repeats: true) { _ in NSSound(named: "Glass")?.play() }
            RunLoop.main.add(t, forMode: .common)
            t.fire()
            timer = t
        }
        // Phones stop ringing after ~45 s anyway; never ring forever if the "ended" event gets lost.
        DispatchQueue.main.asyncAfter(deadline: .now() + 60) { [weak self] in self?.stop() }
    }

    func stop() {
        sound?.stop()
        sound = nil
        timer?.invalidate()
        timer = nil
    }
}

/// Floating "incoming call" card in the top-right corner, like FaceTime's.
final class IncomingCallPanel: NSWindowController {
    init(name: String, detail: String, icon: NSImage?, canDecline: Bool,
         onDecline: @escaping () -> Void, onSilence: @escaping () -> Void) {
        let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 360, height: 96),
                            styleMask: [.nonactivatingPanel, .borderless], backing: .buffered, defer: false)
        panel.level = .statusBar
        panel.isFloatingPanel = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = true
        super.init(window: panel)
        let view = CallCardView(name: name, detail: detail, icon: icon, canDecline: canDecline,
                                onDecline: onDecline, onSilence: onSilence)
        panel.contentView = NSHostingView(rootView: view)
    }

    required init?(coder: NSCoder) { fatalError() }

    func present() {
        guard let panel = window, let screen = NSScreen.main else { return }
        let f = screen.visibleFrame
        panel.setFrameOrigin(NSPoint(x: f.maxX - panel.frame.width - 16, y: f.maxY - panel.frame.height - 12))
        panel.orderFrontRegardless()
    }
}

private struct CallCardView: View {
    let name: String
    let detail: String
    let icon: NSImage?
    let canDecline: Bool
    let onDecline: () -> Void
    let onSilence: () -> Void
    @StateObject private var anim = Pulse()
    private var pulse: Bool { anim.on }

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(Color.green.opacity(0.25)).frame(width: 52, height: 52)
                    .scaleEffect(pulse ? 1.18 : 0.9).opacity(pulse ? 0 : 1)
                Circle().fill(Color.green.opacity(0.18)).frame(width: 46, height: 46)
                Image(systemName: "phone.arrow.down.left").foregroundStyle(Color.green).font(.system(size: 19, weight: .semibold))
            }
            .onAppear { withAnimation(.easeOut(duration: 1.2).repeatForever(autoreverses: false)) { anim.on = true } }
            VStack(alignment: .leading, spacing: 3) {
                Text(name).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                Text(detail.isEmpty ? "Ringing on your phone" : detail)
                    .font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 6)
            HStack(spacing: 10) {
                RoundButton(symbol: "bell.slash.fill", color: Color(white: 0.55), help: "Silence on Mac", action: onSilence)
                if canDecline {
                    RoundButton(symbol: "phone.down.fill", color: .red, help: "Decline", action: onDecline)
                }
            }
        }
        .padding(.horizontal, 18)
        .frame(width: 360, height: 96)
        .background(VisualEffect().clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous)))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Color.primary.opacity(0.08)))
    }
}

/// Holder for the ring animation (the Command Line Tools have no SwiftUI macros, so no @State).
private final class Pulse: ObservableObject { @Published var on = false }

private struct RoundButton: View {
    let symbol: String
    let color: Color
    let help: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 15, weight: .semibold)).foregroundStyle(.white)
                .frame(width: 40, height: 40).background(Circle().fill(color))
        }
        .buttonStyle(.plain)
        .help(help)
    }
}

struct VisualEffect: NSViewRepresentable {
    func makeNSView(context: Context) -> NSVisualEffectView {
        let v = NSVisualEffectView()
        v.material = .hudWindow
        v.blendingMode = .behindWindow
        v.state = .active
        return v
    }
    func updateNSView(_ nsView: NSVisualEffectView, context: Context) {}
}
