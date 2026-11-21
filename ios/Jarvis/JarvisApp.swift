import AppIntents
import SwiftUI

@main
struct JarvisApp: App {
    @StateObject private var assistant = Assistant.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(assistant)
                .preferredColorScheme(.dark)
                .onOpenURL { url in pair(url) }
        }
    }

    /// jarvis://pair?url=…&token=… from the QR code `jarvis.py --serve` prints.
    private func pair(_ url: URL) {
        guard url.host == "pair", let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems else { return }
        for key in ["url", "token"] {
            if let v = items.first(where: { $0.name == key })?.value { UserDefaults.standard.set(v, forKey: key) }
        }
        assistant.log("system", "Paired with \(Backend.url). Testing…")
        Task { assistant.log("system", await Backend.health()) }
    }
}

/// Same world as the web HUD (DESIGN.md): one cyan light on a near-black void, the voice sphere at the centre,
/// the conversation as telemetry, actions in mono, problems in amber.
enum HUD {
    static let void = Color(red: 0.008, green: 0.027, blue: 0.047)
    static let arc = Color(red: 0.39, green: 0.89, blue: 1)
    static let text = Color(red: 0.84, green: 0.96, blue: 0.99)
    static let muted = Color(red: 0.53, green: 0.73, blue: 0.79)
    static let amber = Color(red: 1, green: 0.71, blue: 0.28)
    static let line = Color(red: 0.39, green: 0.89, blue: 1).opacity(0.38)
    static func label(_ s: String, _ color: Color = muted, size: CGFloat = 10.5) -> some View {
        Text(s.uppercased()).font(.system(size: size, weight: .regular, design: .default).width(.expanded))
            .tracking(1.6).foregroundStyle(color)
    }
}

struct ContentView: View {
    @EnvironmentObject var assistant: Assistant
    @State private var typed = ""
    @State private var showSettings = false

    private var mode: SphereView.Mode {
        if assistant.offline { return .offline }
        switch assistant.state {
        case .idle: return .idle
        case .listening: return .listening
        case .thinking: return .thinking
        case .speaking: return .speaking
        }
    }

    private var stateWord: String {
        switch mode {
        case .offline: "Offline"
        case .idle: "Online"
        case .listening: "Listening"
        case .thinking: "Working"
        case .speaking: "Speaking"
        }
    }

    var body: some View {
        VStack(spacing: 10) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("J.A.R.V.I.S.").font(.system(size: 16, weight: .light).width(.expanded)).tracking(5).foregroundStyle(HUD.arc)
                    HUD.label(stateWord)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 6) {
                    TimelineView(.periodic(from: .now, by: 1)) { tl in
                        Text(verbatim: Self.clock.string(from: tl.date))
                            .font(.system(size: 20, weight: .light)).monospacedDigit().foregroundStyle(HUD.text)
                    }
                    Button { showSettings = true } label: { HUD.label("Settings", HUD.arc) }
                }
            }

            Button(action: assistant.micTapped) {
                SphereView(mode: mode) {
                    switch assistant.state {
                    case .listening: assistant.micLevel
                    case .speaking: 0.3 + 0.18 * sin(Date().timeIntervalSinceReferenceDate * 11) // AVSpeech gives no samples
                    default: 0
                    }
                }
                .frame(maxWidth: 340)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(assistant.state == .listening ? "Stop listening" : "Talk to Jarvis")

            HUD.label(assistant.offline ? "Your Mac is not answering" : assistant.status, assistant.offline ? HUD.amber : HUD.arc, size: 11)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(assistant.lines) { line in
                            Group {
                                switch line.who {
                                case "you": Text("›  \(line.text)").font(.system(size: 14)).foregroundStyle(HUD.muted)
                                case "act": Text(line.text).font(.system(size: 12, design: .monospaced)).foregroundStyle(HUD.muted).lineLimit(2)
                                case "jarvis": Text(line.text).font(.system(size: 16)).foregroundStyle(HUD.text).lineSpacing(3)
                                default: Text(line.text).font(.system(size: 14)).foregroundStyle(HUD.amber)
                                }
                            }
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .id(line.id)
                        }
                    }
                }
                .scrollIndicators(.hidden)
                .onChange(of: assistant.lines.count) { _, _ in
                    if let last = assistant.lines.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                }
            }

            HStack(spacing: 12) {
                TextField("", text: $typed, prompt: Text("Type to JARVIS").foregroundStyle(HUD.muted))
                    .foregroundStyle(HUD.text)
                    .submitLabel(.send)
                    .onSubmit(send)
                Button(action: send) { HUD.label("Send", typed.isEmpty ? HUD.muted : HUD.arc, size: 11) }
                    .disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(.vertical, 10)
            .overlay(alignment: .bottom) { Rectangle().fill(HUD.line).frame(height: 1) }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 8)
        .background(
            ZStack {
                HUD.void
                RadialGradient(colors: [HUD.arc.opacity(0.12), .clear], center: UnitPoint(x: 0.5, y: 0.3), startRadius: 0, endRadius: 320)
            }.ignoresSafeArea()
        )
        .tint(HUD.arc)
        .sheet(isPresented: $showSettings) { SettingsView() }
        .task {
            // ponytail: `-ask "…"` launch argument sends a request on start (used to test from the command line)
            if let q = UserDefaults.standard.string(forKey: "ask") { assistant.send(typed: q) }
            if UserDefaults.standard.bool(forKey: "listen") { assistant.startConversation() } // `-listen YES`
        }
    }

    private static let clock: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss" // 24-hour, like the web HUD
        return f
    }()

    private func send() {
        let t = typed.trimmingCharacters(in: .whitespaces)
        if !t.isEmpty { typed = ""; assistant.send(typed: t) }
    }
}

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var assistant: Assistant
    @AppStorage("url") private var url = ""
    @AppStorage("token") private var token = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Your Mac") {
                    TextField("http://192.168.1.10:8765", text: $url).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Token", text: $token)
                    Button("Test connection") { Task { assistant.log("system", await Backend.health()) } }
                }
                Section("Use it like an assistant") {
                    Text("Say “Hey Siri, ask Jarvis” — Siri passes your request to Claude and reads the answer.")
                    Text("Action button: Settings → Action Button → Shortcut → Talk to Jarvis.")
                    Text("Back Tap: Settings → Accessibility → Touch → Back Tap → Talk to Jarvis.")
                }
                Section("Phone access") {
                    Text("Jarvis asks for contacts, calendar, reminders, location and music access the first time it needs them.")
                    Button("Open iPhone Settings") { UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!) }
                }
            }
            .navigationTitle("Settings")
            .toolbar { Button("Done") { dismiss() } }
        }
    }
}
