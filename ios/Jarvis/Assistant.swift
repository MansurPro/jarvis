import AVFoundation
import Foundation
import Speech

/// The conversation loop: listen (on-device speech recognition) → ask the Mac → speak each sentence → listen again.
@MainActor
final class Assistant: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    static let shared = Assistant()

    enum State { case idle, listening, thinking, speaking }
    struct Line: Identifiable { let id = UUID(); let who: String; var text: String }

    @Published var state = State.idle
    @Published var lines: [Line] = []
    @Published var heard = ""
    @Published var status = "Tap the sphere, or say “Hey Siri, ask Jarvis”"
    @Published var offline = false
    /// 0..1 loudness of your voice while listening; read by the sphere every frame (not @Published on purpose).
    var micLevel = 0.0

    private let synth = AVSpeechSynthesizer()
    private let engine = AVAudioEngine()
    private var recognizer = SFSpeechRecognizer()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var recognition: SFSpeechRecognitionTask?
    private var silenceTimer: Timer?
    private var turn: Task<Void, Never>?
    private var turnDone = true
    private var conversing = false

    // Mac speech recognition (Whisper) fallback: used when the iPhone's recognizer is unavailable, or failed
    // before it ever worked. Both facts are remembered between launches.
    private var useMac: Bool {
        get { UserDefaults.standard.string(forKey: "recognizer") == "mac" }
        set { UserDefaults.standard.set(newValue ? "mac" : "phone", forKey: "recognizer") }
    }
    private var recognizerProven: Bool {
        get { UserDefaults.standard.bool(forKey: "recognizer_ok") }
        set { UserDefaults.standard.set(newValue, forKey: "recognizer_ok") }
    }
    private var macActive = false
    private var macPCM = Data()
    private var macVoiced = 0
    private var macFloor = 300.0
    private var macPeak = 0.0
    private var macLast = Date()
    private var macStart = Date()

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
        case .listening: conversing = false; if macActive { finishMac(send: macVoiced >= 4) } else { finishListening() }
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
        // Only ask for Apple speech recognition if we'll use it; Mac transcription needs just the microphone.
        let speechOK = useMac ? false : await withCheckedContinuation { c in
            SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0 == .authorized) }
        }
        let micOK = await AVAudioApplication.requestRecordPermission()
        guard micOK else {
            log("system", "Jarvis needs microphone access. Allow it in Settings → Jarvis.")
            return
        }
        if !speechOK || recognizer?.isAvailable != true { useMac = true }
        let mac = useMac
        synth.stopSpeaking(at: .immediate)
        stopEngine()
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth, .duckOthers])
            try session.setActive(true, options: .notifyOthersOnDeactivation)

            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            // No usable input (another app or a call holds the mic, or no device): installTap would raise an exception.
            guard format.sampleRate > 0, format.channelCount > 0 else {
                throw Actions.Problem("no microphone input is available right now")
            }
            if mac {
                let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: true)!
                guard let conv = AVAudioConverter(from: format, to: target) else { throw Actions.Problem("can't convert microphone audio") }
                input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buf, _ in
                    let cap = AVAudioFrameCount(Double(buf.frameLength) * 16000 / format.sampleRate) + 32
                    guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: cap) else { return }
                    var fed = false
                    conv.convert(to: out, error: nil) { _, status in
                        if fed { status.pointee = .noDataNow; return nil }
                        fed = true; status.pointee = .haveData; return buf
                    }
                    let n = Int(out.frameLength)
                    guard n > 0, let p = out.int16ChannelData?[0] else { return }
                    var sum = 0.0
                    for i in 0..<n { sum += Double(p[i]) * Double(p[i]) }
                    let data = Data(bytes: p, count: n * 2), rms = (sum / Double(n)).squareRoot()
                    Task { @MainActor in self?.macHeard(data, rms: rms) }
                }
            } else {
                let req = SFSpeechAudioBufferRecognitionRequest()
                req.shouldReportPartialResults = true
                if recognizer?.supportsOnDeviceRecognition == true { req.requiresOnDeviceRecognition = true }
                request = req
                input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buf, _ in
                    req.append(buf)
                    guard let ch = buf.floatChannelData?[0], buf.frameLength > 0 else { return }
                    var sum: Float = 0
                    for i in 0..<Int(buf.frameLength) { sum += ch[i] * ch[i] }
                    let level = min(1, Double((sum / Float(buf.frameLength)).squareRoot()) * 12)
                    Task { @MainActor in self?.micLevel = level }
                }
            }
            engine.prepare()
            try engine.start()
        } catch {
            log("system", "Couldn't start the microphone: \(error.localizedDescription)")
            return
        }
        heard = ""
        state = .listening
        if mac {
            status = "Listening… (Mac speech recognition)"
            macActive = true
            macPCM = Data(); macVoiced = 0; macPeak = 0; macStart = Date(); macLast = Date()
            return
        }
        status = "Listening…"
        armSilenceTimer(seconds: 6) // nothing said at all → give up

        let req = request!
        recognition = recognizer?.recognitionTask(with: req) { [weak self] result, error in
            Task { @MainActor in
                guard let self, self.request === req else { return } // ignore callbacks from a cancelled request
                if let result {
                    self.heard = result.bestTranscription.formattedString
                    self.status = "“\(self.heard)”"
                    self.armSilenceTimer(seconds: 1.3) // ponytail: pause-based end of speech; tune if it cuts you off
                    if result.isFinal { self.finishListening() }
                } else if error != nil, self.state == .listening {
                    if self.heard.isEmpty && !self.recognizerProven {
                        self.useMac = true // this recognizer doesn't work here: switch to the Mac and keep listening
                        self.stopEngine()
                        Task { await self.listen() }
                    } else {
                        self.finishListening()
                    }
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
            recognizerProven = true
            ask(text)
        }
    }

    /// Same endpointing as Mac voice mode: adaptive noise floor, ignore clicks, stop after a pause.
    private func macHeard(_ data: Data, rms: Double) {
        guard macActive, state == .listening else { return }
        micLevel = min(1, rms / 2500)
        let now = Date()
        macPCM.append(data)
        if rms > max(200, macFloor * 3, macPeak * 0.15) {
            macVoiced += 1; macLast = now; macPeak = max(macPeak, rms)
        } else if macVoiced == 0 {
            macFloor = 0.9 * macFloor + 0.1 * min(rms, macFloor * 2)
            if macPCM.count > 16000 * 2 / 2 { macPCM = Data(macPCM.suffix(16000 * 2 * 3 / 10)) } // keep 300 ms pre-roll
        }
        let quiet = now.timeIntervalSince(macLast), elapsed = now.timeIntervalSince(macStart)
        if macVoiced == 0 && elapsed > 7 { finishMac(send: false) }
        else if (1...3).contains(macVoiced) && quiet > 0.8 { macVoiced = 0; macPeak = 0 } // a click, not speech
        else if macVoiced >= 4 && quiet > 0.9 { finishMac(send: true) }
        else if elapsed > 25 { finishMac(send: macVoiced >= 4) }
    }

    private func finishMac(send: Bool) {
        guard macActive else { return }
        macActive = false
        stopEngine()
        guard send else { conversing = false; setIdle(); return }
        state = .thinking
        status = "Transcribing…"
        let pcm = macPCM
        Task {
            let text = (try? await Backend.transcribe(pcm)) ?? ""
            if text.isEmpty { conversing = false; setIdle() } else { ask(text) }
        }
    }

    // MARK: - asking

    private func cancelTurn() {
        turn?.cancel()
        turn = nil
        turnDone = true
    }

    static func label(_ name: String) -> String {
        ["Bash": "SHELL", "Read": "READ", "Write": "WRITE", "Edit": "EDIT", "WebSearch": "WEB", "WebFetch": "WEB",
         "Grep": "SEARCH", "Glob": "FIND", "mcp__phone__phone": "PHONE"][name] ?? name.uppercased()
    }

    static func verb(_ name: String) -> String {
        ["Bash": "Running a command", "Read": "Reading a file", "WebSearch": "Searching the web",
         "WebFetch": "Reading a web page", "mcp__phone__phone": "Using your phone"][name] ?? "Working"
    }

    private var replyOpen = false

    func ask(_ text: String) {
        replyOpen = false
        offline = false
        cancelTurn()
        turnDone = false
        log("you", text)
        state = .thinking
        status = "Thinking…"
        turn = Task {
            do {
                try await Backend.ask(text, onText: { [weak self] t in
                    guard let self, !Task.isCancelled else { return }
                    if self.replyOpen, self.lines.last?.who == "jarvis" { self.lines[self.lines.count - 1].text += " " + t }
                    else { self.log("jarvis", t); self.replyOpen = true }
                    self.speak(t)
                }, onTool: { [weak self] action in
                    self?.status = "Using your phone: \(action)"
                }, onActivity: { [weak self] name, detail in
                    guard let self, !Task.isCancelled else { return }
                    self.log("act", "\(Assistant.label(name))  \(detail.prefix(90))")
                    self.replyOpen = false
                    self.status = Assistant.verb(name)
                })
            } catch is CancellationError {
                return
            } catch {
                if Task.isCancelled { return }
                log("system", error.localizedDescription)
                offline = !(error is Backend.Failure)
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
        status = "Tap the sphere, or say “Hey Siri, ask Jarvis”"
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
