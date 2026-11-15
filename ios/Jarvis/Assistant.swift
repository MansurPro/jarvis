import AVFoundation
import Foundation
import Speech

/// The conversation loop: listen (on-device speech recognition) → ask the Mac → speak each sentence → listen again.
@MainActor
final class Assistant: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    static let shared = Assistant()

    enum State { case idle, listening, thinking, speaking }
    struct Line: Identifiable { let id = UUID(); let who: String; let text: String }

    @Published var state = State.idle
    @Published var lines: [Line] = []
    @Published var heard = ""
    @Published var status = "Tap the mic, or say “Hey Siri, ask Jarvis”"

    private let synth = AVSpeechSynthesizer()
    private let engine = AVAudioEngine()
    private var recognizer = SFSpeechRecognizer()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var recognition: SFSpeechRecognitionTask?
    private var silenceTimer: Timer?
    private var turn: Task<Void, Never>?
    private var turnDone = true
    private var conversing = false

    override init() {
        super.init()
        synth.delegate = self
        if !Backend.paired {
            log("system", "Welcome. On your Mac run `uv run jarvis.py --serve`, then scan the QR code it prints with the Camera app (or tap ⚙ to type the URL and token).")
        }
    }

    func log(_ who: String, _ text: String) { lines.append(Line(who: who, text: text)) }

    // MARK: - controls

    func micTapped() {
        switch state {
        case .listening: conversing = false; finishListening()
        case .thinking, .speaking: cancelTurn(); synth.stopSpeaking(at: .immediate); startConversation()
        case .idle: startConversation()
        }
    }

    func startConversation() {
        conversing = true
        Task { await listen() }
    }

    func send(typed text: String) {
        conversing = false
        ask(text)
    }

    // MARK: - listening

    private func listen() async {
        guard Backend.paired else { log("system", Backend.Failure.notPaired.localizedDescription); return }
        let speechOK = await withCheckedContinuation { c in SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0 == .authorized) } }
        let micOK = await AVAudioApplication.requestRecordPermission()
        guard speechOK, micOK else {
            log("system", "Jarvis needs microphone and speech recognition access. Allow them in Settings → Jarvis.")
            return
        }
        synth.stopSpeaking(at: .immediate)
        stopEngine()
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth, .duckOthers])
            try session.setActive(true, options: .notifyOthersOnDeactivation)

            let req = SFSpeechAudioBufferRecognitionRequest()
            req.shouldReportPartialResults = true
            if recognizer?.supportsOnDeviceRecognition == true { req.requiresOnDeviceRecognition = true }
            request = req
            let input = engine.inputNode
            input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { buf, _ in req.append(buf) }
            engine.prepare()
            try engine.start()
        } catch {
            log("system", "Couldn't start the microphone: \(error.localizedDescription)")
            return
        }
        heard = ""
        state = .listening
        status = "Listening…"
        armSilenceTimer(seconds: 6) // nothing said at all → give up

        recognition = recognizer?.recognitionTask(with: request!) { [weak self] result, error in
            Task { @MainActor in
                guard let self else { return }
                if let result {
                    self.heard = result.bestTranscription.formattedString
                    self.status = "“\(self.heard)”"
                    self.armSilenceTimer(seconds: 1.3) // ponytail: pause-based end of speech; tune if it cuts you off
                    if result.isFinal { self.finishListening() }
                } else if error != nil, self.state == .listening {
                    self.finishListening()
                }
            }
        }
    }

    private func armSilenceTimer(seconds: Double) {
        silenceTimer?.invalidate()
        silenceTimer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self] _ in
            Task { @MainActor in self?.finishListening() }
        }
    }

    private func stopEngine() {
        silenceTimer?.invalidate()
        if engine.isRunning { engine.stop() }
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        recognition?.cancel()
        request = nil
        recognition = nil
    }

    private func finishListening() {
        guard state == .listening else { return }
        stopEngine()
        let text = heard.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty {
            conversing = false
            setIdle()
        } else {
            ask(text)
        }
    }

    // MARK: - asking

    private func cancelTurn() {
        turn?.cancel()
        turn = nil
        turnDone = true
    }

    func ask(_ text: String) {
        cancelTurn()
        turnDone = false
        log("you", text)
        state = .thinking
        status = "Thinking…"
        turn = Task {
            do {
                try await Backend.ask(text, onText: { [weak self] t in
                    guard let self, !Task.isCancelled else { return }
                    self.log("jarvis", t)
                    self.speak(t)
                }, onTool: { [weak self] action in
                    self?.status = "Using \(action)…"
                })
            } catch is CancellationError {
                return
            } catch {
                if Task.isCancelled { return }
                log("system", error.localizedDescription)
                conversing = false
                speak("I can't reach your Mac, sir.")
            }
            if Task.isCancelled { return }
            turnDone = true
            followUp()
        }
    }

    // MARK: - speaking

    private lazy var englishVoice: AVSpeechSynthesisVoice? = {
        let gb = AVSpeechSynthesisVoice.speechVoices().filter { $0.language == "en-GB" }
        return gb.filter { $0.gender == .male }.max { $0.quality.rawValue < $1.quality.rawValue }
            ?? gb.max { $0.quality.rawValue < $1.quality.rawValue }
            ?? AVSpeechSynthesisVoice(language: "en-GB")
    }()

    func speak(_ text: String) {
        let u = AVSpeechUtterance(string: text)
        u.voice = text.range(of: "[а-яА-ЯёЁ]", options: .regularExpression) != nil ? AVSpeechSynthesisVoice(language: "ru-RU") : englishVoice
        u.rate = 0.52
        state = .speaking
        status = "Speaking — tap to interrupt"
        synth.speak(u)
    }

    nonisolated func speechSynthesizer(_ s: AVSpeechSynthesizer, didFinish u: AVSpeechUtterance) {
        Task { @MainActor in self.followUp() }
    }

    private func followUp() {
        guard turnDone, !synth.isSpeaking, state != .listening else { return }
        if conversing {
            Task { await listen() }
        } else {
            setIdle()
        }
    }

    private func setIdle() {
        state = .idle
        status = "Tap the mic, or say “Hey Siri, ask Jarvis”"
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
