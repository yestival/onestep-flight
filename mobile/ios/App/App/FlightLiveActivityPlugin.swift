import Foundation
#if !FLIGHT_ACTIVITY_TEST
import ActivityKit
import Capacitor
import UIKit
#endif

@MainActor
final class FlightActivityCoordinator {
    static let shared = FlightActivityCoordinator()
    private var observers: [String: [Task<Void, Never>]] = [:]
    private var ended = Set<String>()
    private let defaults: UserDefaults
    private var operation: Task<Void, Error>?
    private var errorCode = ""
    private var errorReference = ""
    private let pendingStateKey = "flight.pendingActivityState"
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }
    var dismissedKey: String { defaults.string(forKey: "flight.dismissedJourney") ?? "" }
    var current: Activity<FlightActivityAttributes>? {
        Activity<FlightActivityAttributes>.activities.first { $0.activityState == .active || $0.activityState == .stale }
    }
    func restore() {
        let activities = Activity<FlightActivityAttributes>.activities
        for activity in activities {
            if activity.activityState == .ended {
                ended.insert(activity.id)
                defaults.set("ended", forKey: "flight.activityReason." + activity.id)
            }
            if activity.activityState == .dismissed { recordDismissal(activity) }
            observe(activity)
        }
    }
    @discardableResult
    private func recordDismissal(_ activity: Activity<FlightActivityAttributes>) -> Bool {
        let expiry = defaults.double(forKey: "flight.activityNormalEnd." + activity.id)
        let normal = ended.contains(activity.id) || defaults.string(forKey: "flight.activityReason." + activity.id) == "ended" || expiry > 0 && Date().timeIntervalSince1970 >= expiry
        if !normal {
            defaults.set("dismissed", forKey: "flight.activityReason." + activity.id)
            defaults.set(activity.attributes.journeyKey, forKey: "flight.dismissedJourney")
        }
        return normal
    }
    // ActivityKit async calls can interleave even on MainActor. Serialize creation,
    // updates and explicit ends from WebView, foreground recovery and URLSession.
    private func serialize(_ action: @escaping @MainActor () async throws -> Void) async throws {
        let previous = operation
        let next = Task { @MainActor in
            if let previous { _ = await previous.result }
            try await action()
        }
        operation = next
        try await next.value
    }
    func recordFailure(_ error: Error, code: String) {
        errorCode = code
        let nativeError = error as NSError
        errorReference = String(nativeError.domain.prefix(64)) + ":" + String(nativeError.code)
    }
    private func clearFailure() { errorCode = ""; errorReference = "" }
    func resume() async {
        do {
            try await serialize {
                // Read after earlier clear/switch operations, so a queued resume
                // cannot recreate the journey that the traveler just cleared.
                guard let data = self.defaults.data(forKey: self.pendingStateKey),
                      let state = try? JSONDecoder().decode(FlightActivityAttributes.ContentState.self, from: data) else { return }
                try await self.apply(state)
            }
        } catch { recordFailure(error, code: "LIVE_ACTIVITY_REQUEST_FAILED") }
    }
    func observe(_ activity: Activity<FlightActivityAttributes>) {
        guard observers[activity.id] == nil else { return }
        let key = activity.attributes.journeyKey
        observers[activity.id] = [Task { [weak self] in
            for await token in activity.pushTokenUpdates {
                await FlightPushCoordinator.shared.bind(activity: activity.id, journeyKey: key, token: token.map { String(format: "%02x", $0) }.joined(), state: "active")
            }
        }, Task { [weak self] in
            for await state in activity.activityStateUpdates {
                guard let self else { return }
                if state == .ended {
                    self.ended.insert(activity.id)
                    self.defaults.set("ended", forKey: "flight.activityReason." + activity.id)
                    await FlightPushCoordinator.shared.bind(activity: activity.id, journeyKey: key, token: nil, state: "ended")
                }
                if state == .dismissed {
                    let normal = self.recordDismissal(activity)
                    await FlightPushCoordinator.shared.bind(activity: activity.id, journeyKey: key, token: nil, state: normal ? "ended" : "dismissed")
                }
            }
        }, Task {
            for await content in activity.contentUpdates {
                FlightPushCoordinator.shared.acceptActivityState(content.state)
            }
        }]
        if let token = activity.pushToken {
            Task { await FlightPushCoordinator.shared.bind(activity: activity.id, journeyKey: key, token: token.map { String(format: "%02x", $0) }.joined(), state: "active") }
        }
    }
    func end(journeyKey: String? = nil) async {
        try? await serialize { await self.endActivities(journeyKey: journeyKey) }
    }
    private func endActivities(clearPending: Bool = true, journeyKey: String? = nil) async {
        let pending = defaults.data(forKey: pendingStateKey).flatMap { try? JSONDecoder().decode(FlightActivityAttributes.ContentState.self, from: $0) }
        if clearPending && (journeyKey == nil || pending?.journeyKey == journeyKey) { defaults.removeObject(forKey: pendingStateKey) }
        if journeyKey == nil || current?.attributes.journeyKey == journeyKey { clearFailure() }
        if journeyKey == nil || dismissedKey == journeyKey { defaults.removeObject(forKey: "flight.dismissedJourney") }
        for activity in Activity<FlightActivityAttributes>.activities where journeyKey == nil || activity.attributes.journeyKey == journeyKey {
            ended.insert(activity.id)
            defaults.set("ended", forKey: "flight.activityReason." + activity.id)
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }
    func sync(_ proposed: FlightActivityAttributes.ContentState) async throws {
        do { try await serialize { try await self.apply(proposed) } }
        catch { recordFailure(error, code: "LIVE_ACTIVITY_REQUEST_FAILED"); throw error }
    }
    private func apply(_ proposed: FlightActivityAttributes.ContentState) async throws {
        restore()
        var state = FlightPushCoordinator.shared.preferredState(proposed)
        let now = Int64(Date().timeIntervalSince1970)
        if state.journeyEndsAt <= now {
            defaults.removeObject(forKey: pendingStateKey)
            clearFailure()
            for activity in Activity<FlightActivityAttributes>.activities where activity.attributes.journeyKey == state.journeyKey {
                ended.insert(activity.id); defaults.set("ended", forKey: "flight.activityReason." + activity.id)
                await activity.end(nil, dismissalPolicy: .immediate)
            }
            return
        }
        // An activity born with an elapsed staleDate is accepted by ActivityKit,
        // but iOS 26 does not add it to the Lock Screen. Preserve the original
        // freshness deadline and facts in content-state; an already-stale card
        // needs its explicit footer, not another system transition into stale.
        // Updating the existing activity this way also repairs affected installs.
        let freshnessElapsed = state.staleAt <= now
        if freshnessElapsed { state.stale = true }
        let staleDate: Date? = freshnessElapsed ? nil : Date(timeIntervalSince1970: TimeInterval(state.staleAt))
        if let data = try? JSONEncoder().encode(state) { defaults.set(data, forKey: pendingStateKey) }
        guard ActivityAuthorizationInfo().areActivitiesEnabled, dismissedKey != state.journeyKey else { clearFailure(); return }
        let content = ActivityContent(state: state, staleDate: staleDate)
        if let activity = Activity<FlightActivityAttributes>.activities.first(where: {
            $0.attributes.journeyKey == state.journeyKey && ($0.activityState == .active || $0.activityState == .stale)
        }) {
            for duplicate in Activity<FlightActivityAttributes>.activities where duplicate.id != activity.id {
                ended.insert(duplicate.id)
                defaults.set("ended", forKey: "flight.activityReason." + duplicate.id)
                await duplicate.end(nil, dismissalPolicy: .immediate)
            }
            guard state.snapshotVersion >= activity.content.state.snapshotVersion,
                  state.clientRevision >= activity.content.state.clientRevision else { return }
            if state.actionCode == "CANCELLED" {
                ended.insert(activity.id)
                defaults.set("ended", forKey: "flight.activityReason." + activity.id)
                await activity.end(content, dismissalPolicy: .after(Date().addingTimeInterval(1800)))
            } else {
                // Foreground recovery and WebView sync may submit identical
                // content together. Avoid spending another system update on it.
                if activity.content.state != state || activity.content.staleDate != staleDate {
                    await activity.update(content) // Ordinary APNs is the sole alert channel.
                }
            }
            clearFailure()
        } else {
            guard state.actionCode != "CANCELLED" else { clearFailure(); return }
            // Creating while backgrounded is forbidden; keep the structured state
            // and retry on activation without waiting for a network verification.
            guard UIApplication.shared.applicationState != .background else { return }
            await endActivities(clearPending: false)
            let activity = try Activity.request(attributes: FlightActivityAttributes(journeyKey: state.journeyKey), content: content, pushType: .token)
            defaults.set(activity.id, forKey: "flight.lastActivityId")
            defaults.set(state.journeyKey, forKey: "flight.lastActivityJourney")
            defaults.set(min(Date().timeIntervalSince1970 + 8 * 3600, Double(state.journeyEndsAt)), forKey: "flight.activityNormalEnd." + activity.id)
            observe(activity)
            clearFailure()
        }
    }
    func status() -> [String: Any] {
        restore()
        return ["supported": true, "enabled": ActivityAuthorizationInfo().areActivitiesEnabled,
                "active": current != nil, "journeyKey": current?.attributes.journeyKey ?? "", "dismissedJourneyKey": dismissedKey,
                "errorCode": errorCode, "errorReference": errorReference]
    }
}

#if !FLIGHT_ACTIVITY_TEST
@objc(FlightLiveActivityPlugin)
public class FlightLiveActivityPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "FlightLiveActivityPlugin"
    public let jsName = "FlightLiveActivity"
    public let pluginMethods: [CAPPluginMethod] = ["status", "start", "update", "end"].map { CAPPluginMethod(name: $0, returnType: CAPPluginReturnPromise) }
    @objc func status(_ call: CAPPluginCall) { Task { @MainActor in call.resolve(FlightActivityCoordinator.shared.status()) } }
    private func sync(_ call: CAPPluginCall) {
        Task { @MainActor in
            let state: FlightActivityAttributes.ContentState
            do {
                let data = try JSONSerialization.data(withJSONObject: call.jsObjectRepresentation)
                state = try JSONDecoder().decode(FlightActivityAttributes.ContentState.self, from: data)
                guard !state.journeyKey.isEmpty, !state.flightNumber.isEmpty, !state.nextStep.isEmpty else { throw NSError(domain: "FlightActivity", code: 1) }
            } catch {
                FlightActivityCoordinator.shared.recordFailure(error, code: "LIVE_ACTIVITY_STATE_INVALID")
                call.resolve(FlightActivityCoordinator.shared.status())
                return
            }
            try? await FlightActivityCoordinator.shared.sync(state)
            call.resolve(FlightActivityCoordinator.shared.status())
        }
    }
    @objc func start(_ call: CAPPluginCall) { sync(call) }
    @objc func update(_ call: CAPPluginCall) { sync(call) }
    @objc func end(_ call: CAPPluginCall) { Task { @MainActor in
        let key = call.getString("journeyKey")
        await FlightActivityCoordinator.shared.end(journeyKey: key?.isEmpty == false ? key : nil)
        call.resolve(FlightActivityCoordinator.shared.status())
    } }
}
#endif
