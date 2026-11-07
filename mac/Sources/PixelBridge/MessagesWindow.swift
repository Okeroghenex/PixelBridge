import AppKit
import BridgeCore
import SwiftUI

/// SMS history fetched from the phone on demand (nothing is stored on the Mac).
final class MessagesModel: ObservableObject {
    struct Thread: Identifiable, Hashable {
        let id: Int64
        let address: String
        let name: String
        let snippet: String
        let date: Date
        let fromMe: Bool
        let unread: Int
    }

    struct Msg: Identifiable, Hashable {
        let id: Int64
        let body: String
        let date: Date
        let fromMe: Bool
    }

    @Published var threads: [Thread] = []
    @Published var messages: [Int64: [Msg]] = [:]
    @Published var loading = false
    @Published var search = ""
    @Published var error: String?
    @Published var selected: Int64? {
        didSet { if let s = selected, s != oldValue { load(thread: s) } }
    }

    private let server: BridgeServer
    private var timeout: DispatchWorkItem?

    init(server: BridgeServer) { self.server = server }

    func refresh() {
        guard server.hasPhone else {
            error = "Your phone isn't connected. Open Link to Mac on the phone and try again."
            return
        }
        loading = true
        error = nil
        server.send(Message("sms_threads_req"))
        timeout?.cancel()
        let t = DispatchWorkItem { [weak self] in
            guard let self, self.loading else { return }
            self.loading = false
            self.error = "Your phone didn't answer. Make sure Link to Mac is open on it."
        }
        timeout = t
        DispatchQueue.main.asyncAfter(deadline: .now() + 10, execute: t)
        if let s = selected { load(thread: s) }
    }

    func load(thread: Int64) {
        server.send(Message("sms_messages_req", ["threadId": thread]))
    }

    func handle(_ m: Message) {
        if m.string("error") == "permission" {
            loading = false
            error = "On your phone, open Link to Mac and tap “Text message history” to allow access."
            return
        }
        let arr = (try? JSONSerialization.jsonObject(with: m.body)) as? [[String: Any]] ?? []
        func date(_ o: [String: Any]) -> Date {
            Date(timeIntervalSince1970: ((o["date"] as? NSNumber)?.doubleValue ?? 0) / 1000)
        }
        switch m.type {
        case "sms_threads":
            loading = false
            error = nil
            threads = arr.map {
                Thread(id: ($0["threadId"] as? NSNumber)?.int64Value ?? 0,
                       address: $0["address"] as? String ?? "",
                       name: $0["name"] as? String ?? "",
                       snippet: $0["snippet"] as? String ?? "",
                       date: date($0),
                       fromMe: $0["fromMe"] as? Bool ?? false,
                       unread: ($0["unread"] as? NSNumber)?.intValue ?? 0)
            }
            if selected == nil { selected = threads.first?.id }
        case "sms_messages":
            guard let tid = m.int("threadId") else { return }
            messages[tid] = arr.map {
                Msg(id: ($0["id"] as? NSNumber)?.int64Value ?? 0, body: $0["body"] as? String ?? "",
                    date: date($0), fromMe: $0["fromMe"] as? Bool ?? false)
            }
        default: break
        }
    }
}

final class MessagesWindowController: NSWindowController {
    convenience init(model: MessagesModel) {
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 900, height: 620),
                              styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                              backing: .buffered, defer: false)
        window.title = "Messages"
        window.titlebarAppearsTransparent = false
        window.isReleasedWhenClosed = false
        window.setFrameAutosaveName("PixelBridgeMessages")
        window.contentView = NSHostingView(rootView: MessagesView(model: model))
        window.center()
        self.init(window: window)
    }
}

struct MessagesView: View {
    @ObservedObject var model: MessagesModel

    private var filtered: [MessagesModel.Thread] {
        let search = model.search
        guard !search.isEmpty else { return model.threads }
        return model.threads.filter {
            $0.name.localizedCaseInsensitiveContains(search) || $0.address.contains(search)
                || $0.snippet.localizedCaseInsensitiveContains(search)
        }
    }

    var body: some View {
        NavigationSplitView {
            Group {
                if model.threads.isEmpty {
                    EmptyState(loading: model.loading, error: model.error, retry: model.refresh)
                } else {
                    List(filtered, selection: $model.selected) { t in
                        ThreadRow(thread: t).tag(t.id)
                    }
                    .listStyle(.sidebar)
                }
            }
            .frame(minWidth: 270)
            .navigationSplitViewColumnWidth(min: 270, ideal: 300, max: 380)
            .searchable(text: $model.search, placement: .sidebar, prompt: "Search")
        } detail: {
            if let id = model.selected, let thread = model.threads.first(where: { $0.id == id }) {
                ConversationView(thread: thread, messages: model.messages[id])
            } else {
                Text(model.threads.isEmpty ? "" : "Select a conversation").foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .toolbar {
            ToolbarItem {
                Button(action: model.refresh) {
                    if model.loading { ProgressView().controlSize(.small) } else { Image(systemName: "arrow.clockwise") }
                }
                .help("Refresh from phone")
            }
        }
        .onAppear { if model.threads.isEmpty { model.refresh() } }
    }
}

private struct EmptyState: View {
    let loading: Bool
    let error: String?
    let retry: () -> Void
    var body: some View {
        VStack(spacing: 12) {
            if loading {
                ProgressView()
                Text("Loading from your phone…").foregroundStyle(.secondary)
            } else if let error {
                Image(systemName: "iphone.slash").font(.system(size: 30)).foregroundStyle(.secondary)
                Text(error).multilineTextAlignment(.center).foregroundStyle(.secondary).padding(.horizontal)
                Button("Try Again", action: retry)
            } else {
                Text("No text messages").foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct Avatar: View {
    let name: String
    var size: CGFloat = 36
    private var initials: String {
        let letters = name.split(separator: " ").compactMap { $0.first(where: { $0.isLetter }) }
        return letters.isEmpty ? "#" : String(letters.prefix(2)).uppercased()
    }
    private var color: Color {
        let palette: [Color] = [.blue, .purple, .pink, .orange, .teal, .indigo, .green]
        return palette[abs(name.hashValue) % palette.count]
    }
    var body: some View {
        Circle().fill(LinearGradient(colors: [color.opacity(0.75), color], startPoint: .top, endPoint: .bottom))
            .frame(width: size, height: size)
            .overlay(Group {
                if initials == "#" { Image(systemName: "person.fill").font(.system(size: size * 0.45)) }
                else { Text(initials).font(.system(size: size * 0.38, weight: .semibold)) }
            }.foregroundStyle(.white))
    }
}

private struct ThreadRow: View {
    let thread: MessagesModel.Thread
    var body: some View {
        HStack(spacing: 10) {
            Avatar(name: thread.name)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(thread.name).font(.system(size: 13, weight: thread.unread > 0 ? .bold : .semibold)).lineLimit(1)
                    Spacer()
                    Text(shortDate(thread.date)).font(.system(size: 11)).foregroundStyle(.secondary)
                }
                HStack(alignment: .top) {
                    Text((thread.fromMe ? "You: " : "") + thread.snippet)
                        .font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(2)
                    Spacer(minLength: 4)
                    if thread.unread > 0 { Circle().fill(Color.accentColor).frame(width: 8, height: 8).padding(.top, 4) }
                }
            }
        }
        .padding(.vertical, 4)
    }
}

private struct ConversationView: View {
    let thread: MessagesModel.Thread
    let messages: [MessagesModel.Msg]?

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Avatar(name: thread.name, size: 30)
                VStack(alignment: .leading, spacing: 0) {
                    Text(thread.name).font(.system(size: 14, weight: .semibold))
                    if thread.name != thread.address {
                        Text(thread.address).font(.system(size: 11)).foregroundStyle(.secondary)
                    }
                }
                Spacer()
            }
            .padding(.horizontal, 18).padding(.vertical, 10)
            Divider()
            if let messages {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 6) {
                            ForEach(Array(messages.enumerated()), id: \.element.id) { i, msg in
                                if i == 0 || !Calendar.current.isDate(messages[i - 1].date, inSameDayAs: msg.date) {
                                    Text(dayLabel(msg.date)).font(.system(size: 11, weight: .medium))
                                        .foregroundStyle(.secondary).padding(.top, 10)
                                }
                                Bubble(msg: msg).id(msg.id)
                            }
                        }
                        .padding(16)
                    }
                    .onAppear { proxy.scrollTo(messages.last?.id, anchor: .bottom) }
                    .onChange(of: messages) { m in proxy.scrollTo(m.last?.id, anchor: .bottom) }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            Divider()
            Text("Text messages (SMS) only · reply to chats from their notifications")
                .font(.system(size: 11)).foregroundStyle(.tertiary).padding(8)
        }
    }
}

private struct Bubble: View {
    let msg: MessagesModel.Msg
    var body: some View {
        HStack {
            if msg.fromMe { Spacer(minLength: 80) }
            Text(msg.body)
                .textSelection(.enabled)
                .font(.system(size: 13))
                .padding(.horizontal, 12).padding(.vertical, 8)
                .foregroundStyle(msg.fromMe ? Color.white : Color.primary)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(msg.fromMe ? Color(red: 0.24, green: 0.36, blue: 1.0) : Color.primary.opacity(0.08)))
                .help(msg.date.formatted(date: .abbreviated, time: .shortened))
            if !msg.fromMe { Spacer(minLength: 80) }
        }
    }
}

private func shortDate(_ d: Date) -> String {
    let cal = Calendar.current
    if cal.isDateInToday(d) { return d.formatted(date: .omitted, time: .shortened) }
    if cal.isDateInYesterday(d) { return "Yesterday" }
    if let days = cal.dateComponents([.day], from: d, to: Date()).day, days < 7 { return d.formatted(.dateTime.weekday(.wide)) }
    return d.formatted(date: .numeric, time: .omitted)
}

private func dayLabel(_ d: Date) -> String {
    let cal = Calendar.current
    if cal.isDateInToday(d) { return "Today" }
    if cal.isDateInYesterday(d) { return "Yesterday" }
    return d.formatted(date: .complete, time: .omitted)
}
