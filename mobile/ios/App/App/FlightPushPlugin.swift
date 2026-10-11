import ActivityKit
import BackgroundTasks
import Capacitor
import Foundation
import Security
import UIKit
import UserNotifications

private final class FlightPushSessionDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

@MainActor
final class FlightPushCoordinator: NSObject, UNUserNotificationCenterDelegate {
    static let shared = FlightPushCoordinator()
    static let refreshIdentifier = "com.selene.onestepflight.flight-sync"
    private struct Identity: Codable { var id: String; var secret: String }
    private struct Operation: Codable { var id: String; var method: String; var path: String; var body: Data?; var attempt: Int = 0; var dueAt: TimeInterval = 0 }
    private let defaults = UserDefaults.standard
    private var identity: Identity?
    private var queue: [Operation] = []
    private var draining = false
    private var activeOperationId: String?
    private lazy var session = URLSession(configuration: .default, delegate: FlightPushSessionDelegate(), delegateQueue: nil)
    private var retryTask: Task<Void, Never>?
    private var latest: FlightActivityAttributes.ContentState?
    private var started = false
    private var journeyIntent: UInt64 = 0
    private var currentJourney: String { defaults.string(forKey: "flight.currentJourney") ?? "" }
    private var revision: Int64 { Int64(defaults.integer(forKey: "flight.clientRevision")) }
    private var basePath: String { "/api/mobile/installations/" + (identity?.id ?? "") }
    var onRoute: (([String: Any]) -> Void)?
    private var pendingRoute: [String: Any]?
    private var origin: URL? {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "FlightAPIOrigin") as? String,
              let url = URL(string: raw), url.scheme == "https", url.host != nil,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
              url.path.isEmpty || url.path == "/" else { return nil }
        return url
    }
    func start() {
        guard !started else { return }
        UNUserNotificationCenter.current().delegate = self
        do { try loadIdentity() } catch { return }
        started = true
        if let saved = defaults.data(forKey: "flight.operations") { queue = (try? JSONDecoder().decode([Operation].self, from: saved)) ?? [] }
        if let saved = defaults.data(forKey: "flight.serverState") { latest = try? JSONDecoder().decode(FlightActivityAttributes.ContentState.self, from: saved) }
        enqueue("POST", "/api/mobile/installations", ["id": identity!.id])
        // Registration always precedes durable work after a restart, including a recovered server database.
        queue.sort { $0.method == "POST" && $1.method != "POST" }; persist()
        FlightActivityCoordinator.shared.restore()
        Task { await drain(); await recover() }
    }
    private func loadIdentity() throws {
        if identity != nil { return }
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.selene.onestepflight.push", kSecAttrAccount as String: "installation"]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query.merging([kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]) { _, new in new } as CFDictionary, &result)
        if status == errSecSuccess, let data = result as? Data {
            identity = try JSONDecoder().decode(Identity.self, from: data); return
        }
        guard status == errSecItemNotFound else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw NSError(domain: "PushIdentity", code: 1) }
        let created = Identity(id: UUID().uuidString.lowercased(), secret: Data(bytes).base64EncodedString())
        let data = try JSONEncoder().encode(created)
        let saved = SecItemAdd(query.merging([kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]) { _, new in new } as CFDictionary, nil)
        guard saved == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(saved)) }
        identity = created // Credentials exist in Keychain before the first network operation.
    }
    private func nextRevision() -> Int64 { let n = revision + 1; defaults.set(n, forKey: "flight.clientRevision"); return n }
    private func persist() { if let data = try? JSONEncoder().encode(queue) { defaults.set(data, forKey: "flight.operations") } }
    private func enqueue(_ method: String, _ path: String, _ body: [String: Any]? = nil) {
        let data = body.flatMap { try? JSONSerialization.data(withJSONObject: $0, options: [.sortedKeys]) }
        if queue.contains(where: { $0.method == method && $0.path == path && $0.body == data }) { return }
        if method == "PUT" { queue.removeAll { $0.method == method && $0.path == path && $0.id != activeOperationId } }
        queue.append(Operation(id: UUID().uuidString, method: method, path: path, body: data)); persist()
    }
    func initialize() async -> [String: Any] {
        start()
        do { try loadIdentity() } catch { return ["ready": false, "reason": "KEYCHAIN_UNAVAILABLE"] }
        enqueue("POST", "/api/mobile/installations", ["id": identity!.id])
        // Requesting ordinary alerts runs independently from Activity.request.
        Task {
            let center = UNUserNotificationCenter.current()
            let settings = await center.notificationSettings()
            if settings.authorizationStatus == .notDetermined { _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge]) }
            UIApplication.shared.registerForRemoteNotifications()
            await self.updateInstallation()
        }
        Task { await drain() }
        if let route = pendingRoute { onRoute?(route); pendingRoute = nil }
        return status()
    }
    func status() -> [String: Any] { ["ready": origin != nil && identity != nil, "pendingOperations": queue.count, "journeyKey": currentJourney, "clientRevision": revision, "snapshotVersion": latest?.snapshotVersion ?? 0, "pushReady": defaults.bool(forKey: "flight.pushReady"), "pushStatus": defaults.string(forKey: "flight.pushStatus") ?? "APNS_NOT_CONFIGURED"] }
    func setDeviceToken(_ token: Data) {
        defaults.set(token.map { String(format: "%02x", $0) }.joined(), forKey: "flight.deviceToken")
        Task { await updateInstallation() }
    }
    func updateInstallation() async {
        guard identity != nil else { return }
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        let auth: String = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional ? "authorized" : "denied"
        enqueue("PUT", basePath, ["deviceToken": defaults.string(forKey: "flight.deviceToken") ?? "", "authorization": auth,
            "language": defaults.string(forKey: "flight.language") ?? "en", "environment": Bundle.main.object(forInfoDictionaryKey: "FlightAPNSEnvironment") as? String ?? "production"])
        await drain()
    }
    func syncJourney(_ input: [String: Any]) async -> [String: Any] {
        guard let key = input["journeyKey"] as? String, !key.isEmpty else { return status() }
        journeyIntent += 1; let intent = journeyIntent
        if currentJourney != key {
            if !currentJourney.isEmpty { await clearJourney(expectedKey: currentJourney, preserveIntent: true) }
            guard intent == journeyIntent else { return snapshot() }
            latest = nil; defaults.removeObject(forKey: "flight.serverState")
            defaults.set(key, forKey: "flight.currentJourney")
        }
        var body = input
        let semantic: [String: Any] = ["journeyKey": key, "progress": input["progress"] ?? [:], "locale": input["locale"] ?? "en"]
        let fingerprint = (try? JSONSerialization.data(withJSONObject: semantic, options: [.sortedKeys])) ?? Data()
        if defaults.data(forKey: "flight.progressFingerprint") != fingerprint {
            _ = nextRevision(); defaults.set(fingerprint, forKey: "flight.progressFingerprint")
        }
        defaults.set(input["locale"] as? String ?? "en", forKey: "flight.language")
        if let progress = input["progress"] as? [String: Any], let data = try? JSONSerialization.data(withJSONObject: progress) {
            defaults.set(data, forKey: "flight.localProgress")
        }
        if let flight = input["flight"] as? [String: Any] {
            defaults.set(flight["gate"] as? String, forKey: "flight.progressGate")
            defaults.set(flight["terminal"] as? String, forKey: "flight.progressTerminal")
        }
        body["clientRevision"] = revision
        enqueue("PUT", basePath + "/journey", body)
        if let latest, latest.journeyKey == key { try? await FlightActivityCoordinator.shared.sync(preferredState(latest)) }
        for activity in Activity<FlightActivityAttributes>.activities where activity.attributes.journeyKey == key {
            if let token = activity.pushToken {
                await bind(activity: activity.id, journeyKey: key, token: token.map { String(format: "%02x", $0) }.joined(), state: "active")
            }
        }
        await drain()
        return snapshot()
    }
    func clearJourney(expectedKey: String? = nil, preserveIntent: Bool = false) async {
        guard identity != nil else { return }
        let key = currentJourney
        if let expectedKey, !expectedKey.isEmpty, expectedKey != key { return }
        if !preserveIntent { journeyIntent += 1 }
        guard !key.isEmpty else { return }
        let rev = nextRevision()
        queue.removeAll { $0.path.contains("/journey") && $0.method == "PUT" }
        var components = URLComponents(); components.queryItems = [URLQueryItem(name: "clientRevision", value: String(rev)), URLQueryItem(name: "journeyKey", value: key)]
        enqueue("DELETE", basePath + "/journey?" + (components.percentEncodedQuery ?? ""))
        defaults.removeObject(forKey: "flight.currentJourney"); defaults.removeObject(forKey: "flight.progressFingerprint")
        defaults.removeObject(forKey: "flight.localProgress"); defaults.removeObject(forKey: "flight.progressGate"); defaults.removeObject(forKey: "flight.progressTerminal")
        latest = nil; defaults.removeObject(forKey: "flight.serverState")
        await FlightActivityCoordinator.shared.end()
        await drain()
    }
    func bind(activity: String, journeyKey: String, token: String?, state: String) async {
        guard identity != nil, journeyKey == currentJourney else { return }
        let storedId = defaults.string(forKey: "flight.boundActivity")
        if storedId != activity { defaults.set(activity, forKey: "flight.boundActivity"); defaults.set(defaults.integer(forKey: "flight.activityGeneration") + 1, forKey: "flight.activityGeneration") }
        var body: [String: Any] = ["journeyKey": journeyKey, "activityId": activity, "state": state, "generation": defaults.integer(forKey: "flight.activityGeneration")]
        if let token { body["token"] = token }
        enqueue("PUT", basePath + "/journey/activity", body); await drain()
    }
    func preferredState(_ state: FlightActivityAttributes.ContentState) -> FlightActivityAttributes.ContentState {
        var result = state
        if let latest, latest.journeyKey == state.journeyKey, latest.snapshotVersion >= state.snapshotVersion { result = latest }
        let language = result.journeyKey == currentJourney ? defaults.string(forKey: "flight.language") ?? state.locale : state.locale
        if result.journeyKey == currentJourney, let data = defaults.data(forKey: "flight.localProgress"),
           let progress = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            return result.applying(progress: progress, revision: revision, gateAtConfirmation: defaults.string(forKey: "flight.progressGate"), terminalAtConfirmation: defaults.string(forKey: "flight.progressTerminal"), language: language)
        }
        return result.localized(to: language)
    }
    func acceptActivityState(_ state: FlightActivityAttributes.ContentState) {
        guard state.journeyKey == currentJourney, state.snapshotVersion >= (latest?.snapshotVersion ?? 0) else { return }
        latest = state
        if let data = try? JSONEncoder().encode(state) { defaults.set(data, forKey: "flight.serverState") }
    }
    func snapshot() -> [String: Any] {
        var result = status()
        if let latest, let data = try? JSONEncoder().encode(latest), let object = try? JSONSerialization.jsonObject(with: data) { result["displayState"] = object }
        return result
    }
    private func request(_ operation: Operation) async throws -> (Data, HTTPURLResponse) {
        guard let origin, let identity, let url = URL(string: operation.path, relativeTo: origin), url.host == origin.host, url.scheme == "https" else { throw NSError(domain: "PushOrigin", code: 1) }
        var request = URLRequest(url: url, timeoutInterval: 25)
        request.httpMethod = operation.method; request.httpBody = operation.body
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer " + identity.secret, forHTTPHeaderField: "Authorization")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw NSError(domain: "PushHTTP", code: 1) }
        return (data, http)
    }
    func drain() async {
        guard !draining, identity != nil, origin != nil else { return }
        draining = true; defer { draining = false; activeOperationId = nil }
        while let operation = queue.first {
            let delay = operation.dueAt - Date().timeIntervalSince1970
            if delay > 0 { scheduleRetry(delay); return }
            activeOperationId = operation.id
            do {
                let (data, http) = try await request(operation)
                if (200..<300).contains(http.statusCode) {
                    if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                        if let ready = object["pushReady"] as? Bool { defaults.set(ready, forKey: "flight.pushReady") }
                        if let status = object["pushStatus"] as? String { defaults.set(status, forKey: "flight.pushStatus") }
                    }
                    if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any], let state = object["displayState"], JSONSerialization.isValidJSONObject(state),
                       let stateData = try? JSONSerialization.data(withJSONObject: state), let decoded = try? JSONDecoder().decode(FlightActivityAttributes.ContentState.self, from: stateData) {
                        acceptActivityState(decoded)
                        if decoded.journeyKey == currentJourney { try? await FlightActivityCoordinator.shared.sync(decoded) }
                    }
                    queue.removeAll { $0.id == operation.id }; persist(); continue
                }
                if http.statusCode == 409 {
                    if let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any], body["code"] as? String == "RETRY_CONCURRENT_WRITE" {
                        postpone(operation, seconds: 2); return
                    }
                    queue.removeAll { $0.id == operation.id }; persist(); continue
                }
                if (400..<500).contains(http.statusCode) && http.statusCode != 429 {
                    defaults.set("HTTP_\(http.statusCode)", forKey: "flight.uploadError")
                    // Persist failed work for diagnosis and a future credential/configuration recovery; avoid a hot loop.
                    postpone(operation, seconds: 3600); return
                }
                let retry = retryAfter(http.value(forHTTPHeaderField: "Retry-After") ?? "")
                postpone(operation, seconds: max(retry, backoff(operation.attempt))); return
            } catch { postpone(operation, seconds: backoff(operation.attempt)); return }
        }
    }
    private func backoff(_ attempt: Int) -> TimeInterval { [60.0,120,300,900][min(attempt,3)] }
    private func retryAfter(_ value: String) -> TimeInterval {
        if let seconds = Double(value) { return max(0, seconds) }
        let format = DateFormatter(); format.locale = Locale(identifier: "en_US_POSIX")
        format.timeZone = TimeZone(secondsFromGMT: 0); format.dateFormat = "EEE, dd MMM yyyy HH:mm:ss z"
        return max(0, format.date(from: value)?.timeIntervalSinceNow ?? 0)
    }
    private func postpone(_ operation: Operation, seconds: TimeInterval) {
        if let index = queue.firstIndex(where: { $0.id == operation.id }) { queue[index].attempt += 1; queue[index].dueAt = Date().timeIntervalSince1970 + seconds; persist() }
        scheduleRetry(seconds)
    }
    private func scheduleRetry(_ seconds: TimeInterval) {
        retryTask?.cancel()
        retryTask = Task { try? await Task.sleep(nanoseconds: UInt64(min(seconds,3600)*1_000_000_000)); if !Task.isCancelled { await drain() } }
    }
    func recover() async {
        await drain()
        guard !currentJourney.isEmpty, identity != nil else { return }
        do {
            let (data,http) = try await request(Operation(id: UUID().uuidString, method: "GET", path: basePath + "/journey", body: nil))
            if http.statusCode == 200, let object = try JSONSerialization.jsonObject(with: data) as? [String: Any], let state = object["displayState"], JSONSerialization.isValidJSONObject(state) {
                let decoded = try JSONDecoder().decode(FlightActivityAttributes.ContentState.self, from: JSONSerialization.data(withJSONObject: state))
                acceptActivityState(decoded)
                if decoded.journeyKey == currentJourney { try? await FlightActivityCoordinator.shared.sync(decoded) }
            }
        } catch {}
        await updateInstallation()
    }
    func scheduleRefresh() {
        guard !currentJourney.isEmpty else { return }
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.refreshIdentifier)
        let request = BGAppRefreshTaskRequest(identifier: Self.refreshIdentifier)
        request.earliestBeginDate = Date().addingTimeInterval(15*60)
        try? BGTaskScheduler.shared.submit(request)
    }
    func runRefresh(_ task: BGAppRefreshTask) {
        scheduleRefresh()
        let work = Task { await recover(); if !Task.isCancelled { task.setTaskCompleted(success: queue.isEmpty) } }
        task.expirationHandler = { work.cancel(); task.setTaskCompleted(success: false) }
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        await MainActor.run {
            let event = notification.request.content.userInfo["eventId"] as? String ?? notification.request.identifier
            var seen = defaults.stringArray(forKey: "flight.seenEvents") ?? []
            if seen.contains(event) { return [] }
            seen.append(event); defaults.set(Array(seen.suffix(128)), forKey: "flight.seenEvents")
            return [.banner, .list, .sound]
        }
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        await MainActor.run {
            let info = response.notification.request.content.userInfo
            guard let key = info["journeyKey"] as? String, key == currentJourney else { return }
            let route: [String: Any] = ["journeyKey": key, "subscriptionId": info["subscriptionId"] ?? ""]
            pendingRoute = route; onRoute?(route)
            Task { await recover() }
        }
    }
}

@objc(FlightPushPlugin)
public class FlightPushPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "FlightPushPlugin"
    public let jsName = "FlightPush"
    public let pluginMethods: [CAPPluginMethod] = ["initialize","syncJourney","status","clearJourney","openSettings"].map { CAPPluginMethod(name: $0, returnType: CAPPluginReturnPromise) }
    @objc func initialize(_ call: CAPPluginCall) { Task { @MainActor in
        FlightPushCoordinator.shared.onRoute = { [weak self] route in self?.notifyListeners("journeyRoute", data: route) }
        call.resolve(await FlightPushCoordinator.shared.initialize())
    } }
    @objc func syncJourney(_ call: CAPPluginCall) { Task { @MainActor in call.resolve(await FlightPushCoordinator.shared.syncJourney(call.jsObjectRepresentation)) } }
    @objc func status(_ call: CAPPluginCall) { Task { @MainActor in call.resolve(FlightPushCoordinator.shared.snapshot()) } }
    @objc func clearJourney(_ call: CAPPluginCall) { Task { @MainActor in await FlightPushCoordinator.shared.clearJourney(expectedKey: call.getString("journeyKey")); call.resolve() } }
    @objc func openSettings(_ call: CAPPluginCall) { Task { @MainActor in
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }; call.resolve()
    } }
}
