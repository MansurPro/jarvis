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

struct ContentView: View {
    @EnvironmentObject var assistant: Assistant
    @State private var typed = ""
    @State private var showSettings = false

    private var micColor: Color {
        switch assistant.state {
        case .idle, .speaking: return Color(red: 0.05, green: 0.65, blue: 0.91)
        case .listening: return .red
        case .thinking: return .orange
        }
    }

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Text("JARVIS").font(.title2.weight(.light)).tracking(6).foregroundStyle(Color(red: 0.05, green: 0.65, blue: 0.91))
                Spacer()
                Button { showSettings = true } label: { Image(systemName: "gearshape").font(.title3) }
                    .accessibilityLabel("Settings")
            }
            Text(assistant.status).font(.subheadline).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(assistant.lines) { line in
                            Text(line.who == "you" ? "› \(line.text)" : line.text)
                                .foregroundStyle(line.who == "system" ? .secondary : .primary)
                                .font(line.who == "you" ? .body.weight(.semibold) : .body)
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .id(line.id)
                        }
                    }
                }
                .onChange(of: assistant.lines.count) { _, _ in
                    if let last = assistant.lines.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                }
            }

            HStack(spacing: 12) {
                TextField("Type to Jarvis…", text: $typed)
                    .textFieldStyle(.roundedBorder)
                    .submitLabel(.send)
                    .onSubmit {
                        let t = typed.trimmingCharacters(in: .whitespaces)
                        if !t.isEmpty { typed = ""; assistant.send(typed: t) }
                    }
                Button(action: assistant.micTapped) {
                    Image(systemName: assistant.state == .listening ? "waveform" : "mic.fill")
                        .font(.title)
                        .foregroundStyle(.white)
                        .frame(width: 72, height: 72)
                        .background(Circle().fill(micColor))
                }
                .accessibilityLabel("Talk to Jarvis")
            }
        }
        .padding()
        .background(Color(red: 0.04, green: 0.07, blue: 0.13).ignoresSafeArea())
        .sheet(isPresented: $showSettings) { SettingsView() }
        .task {
            // ponytail: `-ask "…"` launch argument sends a request on start (used to test from the command line)
            if let q = UserDefaults.standard.string(forKey: "ask") { assistant.send(typed: q) }
            if UserDefaults.standard.bool(forKey: "listen") { assistant.startConversation() } // `-listen YES`
        }
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
