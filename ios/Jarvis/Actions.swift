import AVFoundation
import Contacts
import CoreLocation
import EventKit
import MediaPlayer
import UIKit
import UserNotifications

/// What Claude can do on an iPhone. Apple's sandbox is stricter than Android's: calls and texts open
/// pre-filled for the user to confirm, and anything else goes through the user's Shortcuts.
enum Actions {
    static let supported = [
        "call", "send_sms", "contacts", "calendar_events", "calendar_add", "reminder_add", "timer", "location",
        "open_app", "open_url", "navigate", "media", "flashlight", "battery", "clipboard_get", "clipboard_set",
        "run_shortcut",
    ]

    static let events = EKEventStore()

    /// Returns (result, isError).
    static func run(_ action: String, _ a: [String: Any]) async -> (String, Bool) {
        do {
            return (try await dispatch(action, a), false)
        } catch {
            return ("\(action) failed: \(error.localizedDescription)", true)
        }
    }

    struct Problem: LocalizedError {
        let errorDescription: String?
        init(_ s: String) { errorDescription = s }
    }

    static func str(_ a: [String: Any], _ k: String) throws -> String {
        guard let v = a[k] else { throw Problem("missing \(k)") }
        return "\(v)"
    }

    @MainActor static func open(_ s: String) async throws -> String {
        guard let u = URL(string: s) else { throw Problem("bad URL \(s)") }
        guard await UIApplication.shared.open(u) else { throw Problem("nothing on this iPhone opens \(s)") }
        return "Opened."
    }

    static func q(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed.subtracting(CharacterSet(charactersIn: "&+=?"))) ?? s }

    static func dispatch(_ action: String, _ a: [String: Any]) async throws -> String {
        switch action {
        case "call":
            let n = try str(a, "number").filter { "+0123456789".contains($0) }
            _ = try await open("tel:\(n)")
            return "Started the call (iOS asks the user to confirm)."
        case "send_sms":
            let to = try str(a, "to").filter { "+0123456789".contains($0) }
            _ = try await open("sms:\(to)&body=\(q(try str(a, "body")))")
            return "Opened Messages with the text filled in. iOS requires the user to tap Send."
        case "contacts":
            return try await contacts(a["query"] as? String ?? "")
        case "calendar_events":
            guard try await events.requestFullAccessToEvents() else { throw Problem("calendar access denied") }
            let days = a["days"] as? Int ?? 1
            let found = events.events(matching: events.predicateForEvents(
                withStart: Date(), end: Date().addingTimeInterval(Double(max(days, 1)) * 86400), calendars: nil))
            let f = DateFormatter(); f.dateFormat = "EEE d MMM HH:mm"
            let lines = found.prefix(30).map { "\(f.string(from: $0.startDate)) \($0.title ?? "")" + (($0.location ?? "").isEmpty ? "" : " @ \($0.location!)") }
            return lines.isEmpty ? "Nothing on the calendar." : lines.joined(separator: "\n")
        case "calendar_add":
            guard try await events.requestFullAccessToEvents() else { throw Problem("calendar access denied") }
            let e = EKEvent(eventStore: events)
            e.title = try str(a, "title")
            e.startDate = try date(str(a, "start"))
            e.endDate = (a["end"] as? String).flatMap { try? date($0) } ?? e.startDate.addingTimeInterval(3600)
            e.location = a["location"] as? String
            e.calendar = events.defaultCalendarForNewEvents
            try events.save(e, span: .thisEvent)
            return "Added \(e.title!)."
        case "reminder_add":
            guard try await events.requestFullAccessToReminders() else { throw Problem("reminders access denied") }
            let r = EKReminder(eventStore: events)
            r.title = try str(a, "title")
            r.calendar = events.defaultCalendarForNewReminders()
            if let due = (a["due"] as? String).flatMap({ try? date($0) }) {
                r.dueDateComponents = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: due)
                r.addAlarm(EKAlarm(absoluteDate: due))
            }
            try events.save(r, commit: true)
            return "Reminder added."
        case "timer":
            let secs = Double(try str(a, "seconds")) ?? 60
            let center = UNUserNotificationCenter.current()
            guard try await center.requestAuthorization(options: [.alert, .sound]) else { throw Problem("notifications denied") }
            let c = UNMutableNotificationContent()
            c.title = a["label"] as? String ?? "Timer"
            c.body = "Time's up, sir."
            c.sound = .defaultCritical
            try await center.add(UNNotificationRequest(identifier: UUID().uuidString, content: c,
                                                       trigger: UNTimeIntervalNotificationTrigger(timeInterval: max(secs, 1), repeats: false)))
            return "Timer set for \(Int(secs)) seconds (as a notification)."
        case "location":
            return try await Locator().locate()
        case "open_app":
            return try await openApp(try str(a, "name"))
        case "open_url":
            return try await open(try str(a, "url"))
        case "navigate":
            _ = try await open("maps://?daddr=\(q(try str(a, "destination")))&dirflg=d")
            return "Directions started in Maps."
        case "media":
            return try await media(try str(a, "command").lowercased())
        case "flashlight":
            guard let d = AVCaptureDevice.default(for: .video), d.hasTorch else { throw Problem("no flashlight") }
            let on = a["on"] as? Bool ?? (d.torchMode == .off)
            try d.lockForConfiguration()
            d.torchMode = on ? .on : .off
            d.unlockForConfiguration()
            return "Flashlight \(on ? "on" : "off")."
        case "battery":
            return await MainActor.run {
                UIDevice.current.isBatteryMonitoringEnabled = true
                let level = Int((UIDevice.current.batteryLevel * 100).rounded())
                let charging = [.charging, .full].contains(UIDevice.current.batteryState)
                return level < 0 ? "Battery level unavailable." : "Battery \(level)%, \(charging ? "charging" : "not charging")."
            }
        case "clipboard_get":
            return await MainActor.run { UIPasteboard.general.string ?? "Clipboard is empty." }
        case "clipboard_set":
            let t = try str(a, "text")
            await MainActor.run { UIPasteboard.general.string = t }
            return "Copied."
        case "run_shortcut":
            var u = "shortcuts://run-shortcut?name=\(q(try str(a, "name")))"
            if let input = a["input"] as? String { u += "&input=text&text=\(q(input))" }
            _ = try await open(u)
            return "Started the shortcut."
        default:
            throw Problem("\(action) isn't available on iPhone")
        }
    }

    static func date(_ s: String) throws -> Date {
        for fmt in ["yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"] {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = fmt
            if let d = f.date(from: s) { return d }
        }
        if let d = ISO8601DateFormatter().date(from: s) { return d }
        throw Problem("can't read the date \(s)")
    }

    static func contacts(_ query: String) async throws -> String {
        let store = CNContactStore()
        guard try await store.requestAccess(for: .contacts) else { throw Problem("contacts access denied") }
        let keys = [CNContactGivenNameKey, CNContactFamilyNameKey, CNContactPhoneNumbersKey, CNContactEmailAddressesKey] as [CNKeyDescriptor]
        let found = try store.unifiedContacts(matching: CNContact.predicateForContacts(matchingName: query), keysToFetch: keys)
        let lines = found.prefix(15).map { c in
            let name = [c.givenName, c.familyName].filter { !$0.isEmpty }.joined(separator: " ")
            let phones = c.phoneNumbers.map { $0.value.stringValue }
            let mails = c.emailAddresses.map { $0.value as String }
            return "\(name): " + (phones + mails).joined(separator: ", ")
        }
        return lines.isEmpty ? "No contacts match \"\(query)\"." : lines.joined(separator: "\n")
    }

    /// iOS only lets apps open other apps by URL scheme; these are the common ones.
    static let schemes: [String: String] = [
        "messages": "sms:", "phone": "tel:", "mail": "message:", "maps": "maps://", "music": "music://",
        "photos": "photos-redirect://", "camera": "camera://", "settings": "app-settings:", "safari": "x-web-search://",
        "calendar": "calshow://", "notes": "mobilenotes://", "reminders": "x-apple-reminderkit://", "facetime": "facetime://",
        "app store": "itms-apps://", "shortcuts": "shortcuts://", "podcasts": "podcasts://", "books": "ibooks://",
        "spotify": "spotify://", "youtube": "youtube://", "whatsapp": "whatsapp://", "telegram": "tg://",
        "instagram": "instagram://", "twitter": "twitter://", "x": "twitter://", "gmail": "googlegmail://",
        "google maps": "comgooglemaps://", "chrome": "googlechrome://", "slack": "slack://", "zoom": "zoomus://",
        "uber": "uber://", "netflix": "nflx://", "tiktok": "snssdk1233://", "linkedin": "linkedin://",
    ]

    static func openApp(_ name: String) async throws -> String {
        let key = name.lowercased().trimmingCharacters(in: .whitespaces)
        let scheme = schemes[key] ?? "\(key.replacingOccurrences(of: " ", with: ""))://"
        do {
            _ = try await open(scheme)
            return "Opened \(name)."
        } catch {
            throw Problem("iOS couldn't open \(name). If the user has a Shortcut for it, use run_shortcut.")
        }
    }

    @MainActor static func media(_ command: String) async throws -> String {
        guard await MPMediaLibrary.requestAuthorization() == .authorized else { throw Problem("Apple Music access denied") }
        let p = MPMusicPlayerController.systemMusicPlayer
        switch command {
        case "play": p.play()
        case "pause", "stop": p.pause()
        case "next", "skip": p.skipToNextItem()
        case "previous", "back": p.skipToPreviousItem()
        default: throw Problem("unknown media command \(command)")
        }
        return "Done (Apple Music)."
    }
}

/// One-shot location + reverse geocoding.
final class Locator: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var cont: CheckedContinuation<CLLocation, Error>?

    @MainActor func locate() async throws -> String {
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
        let loc = try await withCheckedThrowingContinuation { c in
            cont = c
            manager.requestLocation()
        }
        let place = try? await CLGeocoder().reverseGeocodeLocation(loc).first
        let addr = [place?.name, place?.locality, place?.country].compactMap { $0 }.joined(separator: ", ")
        return String(format: "%.5f, %.5f (±%.0f m)", loc.coordinate.latitude, loc.coordinate.longitude, loc.horizontalAccuracy)
            + (addr.isEmpty ? "" : " — \(addr)")
    }

    func locationManager(_ m: CLLocationManager, didUpdateLocations locs: [CLLocation]) {
        if let l = locs.last { cont?.resume(returning: l); cont = nil }
    }

    func locationManager(_ m: CLLocationManager, didFailWithError error: Error) {
        cont?.resume(throwing: error); cont = nil
    }

    func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        if [.denied, .restricted].contains(m.authorizationStatus) {
            cont?.resume(throwing: Actions.Problem("location access denied")); cont = nil
        }
    }
}
