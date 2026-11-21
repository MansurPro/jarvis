import Foundation
import UIKit

/// Talks to `jarvis.py --serve` on the Mac: streams replies and runs the phone actions Claude asks for.
struct Backend {
    static var url: String { (UserDefaults.standard.string(forKey: "url") ?? "").trimmingCharacters(in: CharacterSet(charactersIn: "/")) }
    static var token: String { UserDefaults.standard.string(forKey: "token") ?? "" }
    static var paired: Bool { !url.isEmpty && !token.isEmpty }

    enum Failure: LocalizedError {
        case notPaired, badToken, status(Int)
        var errorDescription: String? {
            switch self {
            case .notPaired: return "Jarvis isn't paired with your Mac yet. Open the app and scan the QR code."
            case .badToken: return "Your Mac rejected the pairing token. Scan the QR code again."
            case .status(let c): return "Your Mac answered with error \(c)."
            }
        }
    }

    static func request(_ path: String, body: [String: Any]? = nil) throws -> URLRequest {
        guard paired, let u = URL(string: url + path) else { throw Failure.notPaired }
        var r = URLRequest(url: u, timeoutInterval: 120)
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let body {
            r.httpMethod = "POST"
            r.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        return r
    }

    static func health() async -> String {
        do {
            let (_, resp) = try await URLSession.shared.data(for: request("/health"))
            switch (resp as? HTTPURLResponse)?.statusCode {
            case 200: return "Connected to your Mac."
            case 401: return "Wrong token."
            default: return "Unexpected reply from \(url)."
            }
        } catch {
            return "Can't reach \(url): \(error.localizedDescription)"
        }
    }

    /// Whisper on the Mac, for when the iPhone's own recognizer is missing or broken (e.g. the simulator).
    /// Takes raw 16 kHz mono 16-bit PCM.
    static func transcribe(_ pcm: Data) async throws -> String {
        var r = try request("/transcribe")
        r.httpMethod = "POST"
        r.httpBody = pcm
        r.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        let (data, _) = try await URLSession.shared.data(for: r)
        let text = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["text"] as? String
        return (text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    @MainActor static func device() -> [String: Any] {
        let f = DateFormatter()
        f.dateFormat = "EEE yyyy-MM-dd HH:mm xxx"
        return ["platform": "iOS \(UIDevice.current.systemVersion)", "model": UIDevice.current.model,
                "time": f.string(from: Date()), "actions": Actions.supported]
    }

    /// Sends one request. `onText` gets each sentence as it streams in; tool calls are executed here.
    static func ask(_ text: String, onText: @escaping @MainActor (String) -> Void,
                    onTool: @escaping @MainActor (String) -> Void = { _ in },
                    onActivity: @escaping @MainActor (String, String) -> Void = { _, _ in }) async throws {
        let device = await device()
        let (bytes, resp) = try await URLSession.shared.bytes(for: request("/ask", body: ["text": text, "device": device]))
        switch (resp as? HTTPURLResponse)?.statusCode ?? 0 {
        case 200: break
        case 401: throw Failure.badToken
        case let c: throw Failure.status(c)
        }
        for try await line in bytes.lines {
            try Task.checkCancellation()
            guard let ev = try? JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any] else { continue }
            switch ev["type"] as? String {
            case "text":
                if let t = ev["text"] as? String { await onText(t) }
            case "tool":
                let action = ev["action"] as? String ?? ""
                await onTool(action)
                let (result, isError) = await Actions.run(action, ev["args"] as? [String: Any] ?? [:])
                _ = try? await URLSession.shared.data(for: request("/tool_result", body: [
                    "id": ev["id"] as? String ?? "", "result": result, "error": isError]))
            case "activity": // every action Claude takes on the Mac, for the feed
                await onActivity(ev["name"] as? String ?? "", ev["detail"] as? String ?? "")
            case "done":
                return
            default:
                continue
            }
        }
    }
}
