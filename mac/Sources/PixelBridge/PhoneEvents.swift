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
        let answer = UNNotificationAction(identifier: "answer", title: "Answer", options: [])
        UNUserNotificationCenter.current().setNotificationCategories([
            UNNotificationCategory(identifier: Self.replyCategory, actions: [reply, dismiss], intentIdentifiers: []),
            UNNotificationCategory(identifier: Self.messageCategory, actions: [dismiss], intentIdentifiers: []),
            UNNotificationCategory(identifier: Self.callCategory, actions: [answer, decline], intentIdentifiers: []),
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
        case "call_action_result": handleCallResult(m)
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
            else if actionIdentifier == "answer" { answer(key: callKey) }
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

    private var callModel: CallCardModel?

    private func handleCall(_ m: Message) {
        guard let key = m.string("key") else { return }
        switch m.string("state") {
        case "ringing":
            guard ringForCalls else { return }
            ringingKey = key
            DebugHooks.lastCallKey = key
            let name = m.string("name") ?? "Unknown caller"
            let detail = m.string("detail") ?? ""
            let model = CallCardModel(key: key, name: name, detail: detail,
                                      canAnswer: (m.header["canAnswer"] as? Bool) ?? false,
                                      canDecline: (m.header["canDecline"] as? Bool) ?? false)
            showCallPanel(model)
            if canNotify {
                let c = UNMutableNotificationContent()
                c.title = "\(name) is calling"
                c.body = detail.isEmpty ? "Incoming call on your phone" : detail
                c.categoryIdentifier = Self.callCategory
                c.userInfo = ["callKey": key]
                c.interruptionLevel = .timeSensitive
                UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "call:" + key, content: c, trigger: nil))
            }
        case "active":
            // Picked up (from the Mac or on the phone): stop ringing, show the in-call card.
            ringer?.stop()
            ringer = nil
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["call:" + (ringingKey ?? key)])
            ringingKey = key
            guard let model = callModel else { return } // user had silenced the card
            model.key = key
            model.canHangUp = (m.header["canHangUp"] as? Bool) ?? false
            if model.phase != .active { model.startedAt = Date() }
            model.phase = .active
        default: // "ended"
            endCall(key: key)
        }
    }

    private func handleCallResult(_ m: Message) {
        guard (m.header["ok"] as? Bool) == false, let model = callModel else { return }
        if m.string("action") == "answer" {
            model.phase = .ringing
            model.note = "Couldn't answer from the Mac. Pick up on your phone."
        }
    }

    private func showCallPanel(_ model: CallCardModel) {
        callPanel?.close()
        model.onAnswer = { [weak self, weak model] in
            guard let self, let model else { return }
            self.answer(key: model.key)
        }
        model.onDecline = { [weak self, weak model] in
            guard let model else { return }
            self?.decline(key: model.key)
        }
        model.onHangUp = { [weak self, weak model] in
            guard let model else { return }
            self?.server.send(Message("call_action", ["key": model.key, "action": "hangup"]))
        }
        model.onSilence = { [weak self] in self?.silence() }
        callModel = model
        let panel = IncomingCallPanel(model: model)
        callPanel = panel
        panel.present()
        ringer = Ringer()
        ringer?.start()
    }

    /// Stop the Mac ringing and hide the card; the call itself is untouched.
    private func silence() {
        ringer?.stop()
        ringer = nil
        callPanel?.close()
        callPanel = nil
        callModel = nil
    }

    func sendReply(key: String, text: String) {
        server.send(Message("notif_reply", ["key": key], body: Data(text.utf8)))
    }

    func answer(key: String) {
        server.send(Message("call_action", ["key": key, "action": "answer"]))
        ringer?.stop()
        ringer = nil
        callModel?.note = nil
        callModel?.phase = .answering
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["call:" + key])
    }

    func decline(key: String) {
        server.send(Message("call_action", ["key": key, "action": "decline"]))
        endCall(key: key)
    }

    private func endCall(key: String) {
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

/// State shared between PhoneEvents and the floating call card.
final class CallCardModel: ObservableObject {
    enum Phase { case ringing, answering, active }
    @Published var phase: Phase = .ringing
    @Published var canHangUp = false
    @Published var note: String?
    var startedAt = Date()
    var key: String
    let name: String
    let detail: String
    let canAnswer: Bool
    let canDecline: Bool
    var onAnswer: () -> Void = {}
    var onDecline: () -> Void = {}
    var onHangUp: () -> Void = {}
    var onSilence: () -> Void = {}

    init(key: String, name: String, detail: String, canAnswer: Bool, canDecline: Bool) {
        self.key = key
        self.name = name
        self.detail = detail
        self.canAnswer = canAnswer
        self.canDecline = canDecline
    }
}

/// Floating call card in the top-right corner, like FaceTime's.
final class IncomingCallPanel: NSWindowController {
    init(model: CallCardModel) {
        let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 400, height: 96),
                            styleMask: [.nonactivatingPanel, .borderless], backing: .buffered, defer: false)
        panel.level = .statusBar
        panel.isFloatingPanel = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = true
        super.init(window: panel)
        panel.contentView = NSHostingView(rootView: CallCardView(model: model))
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
    @ObservedObject var model: CallCardModel
    @StateObject private var anim = Pulse()

    private var ringing: Bool { model.phase == .ringing }

    var body: some View {
        HStack(spacing: 14) {
            leadingCircle
                .onAppear { withAnimation(.easeOut(duration: 1.2).repeatForever(autoreverses: false)) { anim.on = true } }

            VStack(alignment: .leading, spacing: 3) {
                Text(model.name).font(.system(size: 15, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.85)
                subtitle.font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(2)
            }
            Spacer(minLength: 6)
            HStack(spacing: 10) {
                switch model.phase {
                case .ringing:
                    if model.canDecline {
                        RoundButton(symbol: "phone.down.fill", color: .red, help: "Decline", action: model.onDecline)
                    }
                    RoundButton(symbol: "bell.slash.fill", color: Color(white: 0.55), help: "Mute ringing on Mac", action: model.onSilence)
                case .answering:
                    EmptyView()
                case .active:
                    if model.canHangUp {
                        RoundButton(symbol: "phone.down.fill", color: .red, help: "End call", action: model.onHangUp)
                    }
                    RoundButton(symbol: "xmark", color: Color(white: 0.55), help: "Hide", action: model.onSilence)
                }
            }
        }
        .padding(.horizontal, 18)
        .frame(width: 400, height: 96)
        .background(VisualEffect().clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous)))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Color.primary.opacity(0.08)))
    }

    /// Ringing: the big green Answer button (pulsing). Otherwise: a status badge.
    @ViewBuilder private var leadingCircle: some View {
        ZStack {
            if ringing {
                Circle().fill(Color.green.opacity(0.35)).frame(width: 58, height: 58)
                    .scaleEffect(anim.on ? 1.2 : 0.92).opacity(anim.on ? 0 : 1)
            }
            if ringing && model.canAnswer {
                Button(action: model.onAnswer) {
                    Image(systemName: "phone.fill").font(.system(size: 22, weight: .semibold)).foregroundStyle(.white)
                        .frame(width: 54, height: 54).background(Circle().fill(Color.green))
                }
                .buttonStyle(.plain)
                .help("Answer (talk on your phone)")
            } else if model.phase == .answering {
                Circle().fill(Color.green).frame(width: 54, height: 54)
                    .overlay(ProgressView().controlSize(.small).tint(.white))
            } else {
                Circle().fill(Color.green.opacity(0.18)).frame(width: 54, height: 54)
                    .overlay(Image(systemName: ringing ? "phone.arrow.down.left" : "phone.connection.fill")
                        .foregroundStyle(Color.green).font(.system(size: 20, weight: .semibold)))
            }
        }
        .frame(width: 58, height: 58)
    }

    @ViewBuilder private var subtitle: some View {
        if let note = model.note {
            Text(note)
        } else {
            switch model.phase {
            case .ringing: Text(model.detail.isEmpty ? "Ringing on your phone" : model.detail)
            case .answering: Text("Answering on your phone…")
            case .active:
                TimelineView(.periodic(from: model.startedAt, by: 1)) { ctx in
                    Text("On call · \(duration(ctx.date.timeIntervalSince(model.startedAt))) · talk on your phone")
                }
            }
        }
    }

    private func duration(_ t: TimeInterval) -> String {
        let s = max(0, Int(t))
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
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
