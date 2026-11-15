import AppIntents

/// "Hey Siri, ask Jarvis" → Siri asks what you want, Claude answers, Siri reads it out.
struct AskJarvis: AppIntent {
    static var title: LocalizedStringResource = "Ask Jarvis"
    static var description = IntentDescription("Ask Jarvis (Claude on your Mac) to answer a question or do something.")

    @Parameter(title: "Request", requestValueDialog: "What can I do for you, sir?")
    var request: String

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<String> {
        var reply: [String] = []
        try await Backend.ask(request, onText: { reply.append($0) })
        let text = reply.joined(separator: " ")
        return .result(value: text, dialog: IntentDialog(stringLiteral: text.isEmpty ? "Done, sir." : text))
    }
}

/// Opens Jarvis already listening: for the Action button, Back Tap, Control Center or a widget.
struct TalkToJarvis: AppIntent {
    static var title: LocalizedStringResource = "Talk to Jarvis"
    static var description = IntentDescription("Open Jarvis and start a voice conversation.")
    static var openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        Assistant.shared.startConversation()
        return .result()
    }
}

struct JarvisShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: AskJarvis(), phrases: ["Ask \(.applicationName)", "Hey \(.applicationName)", "\(.applicationName)"],
                    shortTitle: "Ask Jarvis", systemImageName: "waveform")
        AppShortcut(intent: TalkToJarvis(), phrases: ["Talk to \(.applicationName)", "Start \(.applicationName)"],
                    shortTitle: "Talk to Jarvis", systemImageName: "mic.fill")
    }
}
