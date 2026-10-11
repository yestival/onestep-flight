import ActivityKit
import Capacitor
import Foundation

@MainActor
final class FlightActivityCoordinator {
    static let shared = FlightActivityCoordinator()
    private var observers: [String: [Task<Void, Never>]] = [:]
    private var ended = Set<String>()
    private let defaults = UserDefaults.standard
    var dismissedKey: String { defaults.string(forKey: "flight.dismissedJourney") ?? "" }
    var current: Activity<FlightActivityAttributes>? {
        Activity<FlightActivityAttributes>.activities.first { $0.activityState == .active || $0.activityState == .stale }
    }
    func restore() {
        let activities = Activity<FlightActivityAttributes>.activities
        if let id = defaults.string(forKey: "flight.lastActivityId"),
           let key = defaults.string(forKey: "flight.lastActivityJourney"),
           !activities.contains(where: { $0.id == id }),
           defaults.string(forKey: "flight.activityReason." + id) != "ended",
           Date().timeIntervalSince1970 < defaults.double(forKey: "flight.activityNormalEnd." + id) {
            defaults.set(key, forKey: "flight.dismissedJourney")
        }
        for activity in activities {
            if activity.activityState == .ended {
                ended.insert(activity.id)
                defaults.set("ended", forKey: "flight.activityReason." + activity.id)
            }
            observe(activity)
        }
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
                    let expiry = self.defaults.double(forKey: "flight.activityNormalEnd." + activity.id)
                    let normal = self.ended.contains(activity.id) || self.defaults.string(forKey: "flight.activityReason." + activity.id) == "ended" || expiry > 0 && Date().timeIntervalSince1970 >= expiry
                    if !normal { self.defaults.set(key, forKey: "flight.dismissedJourney") }
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
    func end() async {
        defaults.removeObject(forKey: "flight.dismissedJourney")
        for activity in Activity<FlightActivityAttributes>.activities {
            ended.insert(activity.id)
            defaults.set("ended", forKey: "flight.activityReason." + activity.id)
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }
    func sync(_ proposed: FlightActivityAttributes.ContentState) async throws {
        restore()
        let state = FlightPushCoordinator.shared.preferredState(proposed)
        if state.journeyEndsAt <= Int64(Date().timeIntervalSince1970) {
            for activity in Activity<FlightActivityAttributes>.activities where activity.attributes.journeyKey == state.journeyKey {
                ended.insert(activity.id); defaults.set("ended", forKey: "flight.activityReason." + activity.id)
                await activity.end(nil, dismissalPolicy: .immediate)
            }
            return
        }
        guard ActivityAuthorizationInfo().areActivitiesEnabled, dismissedKey != state.journeyKey else { return }
        let content = ActivityContent(state: state, staleDate: Date(timeIntervalSince1970: TimeInterval(state.staleAt)))
        if let activity = Activity<FlightActivityAttributes>.activities.first(where: {
            $0.attributes.journeyKey == state.journeyKey && ($0.activityState == .active || $0.activityState == .stale)
        }) {
            for duplicate in Activity<FlightActivityAttributes>.activities where duplicate.id != activity.id {
                ended.insert(duplicate.id)
                defaults.set("ended", forKey: "flight.activityReason." + duplicate.id)
                await duplicate.end(nil, dismissalPolicy: .immediate)
            }
            guard state.snapshotVersion >= activity.content.state.snapshotVersion else { return }
            if state.actionCode == "CANCELLED" {
                ended.insert(activity.id)
                defaults.set("ended", forKey: "flight.activityReason." + activity.id)
                await activity.end(content, dismissalPolicy: .after(Date().addingTimeInterval(1800)))
            } else {
                await activity.update(content) // Ordinary APNs is the sole alert channel.
            }
        } else {
            guard state.actionCode != "CANCELLED" else { return }
            await end()
            let activity = try Activity.request(attributes: FlightActivityAttributes(journeyKey: state.journeyKey), content: content, pushType: .token)
            defaults.set(activity.id, forKey: "flight.lastActivityId")
            defaults.set(state.journeyKey, forKey: "flight.lastActivityJourney")
            defaults.set(min(Date().timeIntervalSince1970 + 8 * 3600, Double(state.journeyEndsAt)), forKey: "flight.activityNormalEnd." + activity.id)
            observe(activity)
        }
    }
    func status() -> [String: Any] {
        restore()
        return ["supported": true, "enabled": ActivityAuthorizationInfo().areActivitiesEnabled,
                "active": current != nil, "journeyKey": current?.attributes.journeyKey ?? "", "dismissedJourneyKey": dismissedKey]
    }
}

@objc(FlightLiveActivityPlugin)
public class FlightLiveActivityPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "FlightLiveActivityPlugin"
    public let jsName = "FlightLiveActivity"
    public let pluginMethods: [CAPPluginMethod] = ["status", "start", "update", "end"].map { CAPPluginMethod(name: $0, returnType: CAPPluginReturnPromise) }
    @objc func status(_ call: CAPPluginCall) { Task { @MainActor in call.resolve(FlightActivityCoordinator.shared.status()) } }
    private func sync(_ call: CAPPluginCall) {
        Task { @MainActor in
            do {
                let data = try JSONSerialization.data(withJSONObject: call.options)
                let state = try JSONDecoder().decode(FlightActivityAttributes.ContentState.self, from: data)
                guard !state.journeyKey.isEmpty, !state.flightNumber.isEmpty, !state.nextStep.isEmpty else { throw NSError(domain: "FlightActivity", code: 1) }
                try await FlightActivityCoordinator.shared.sync(state)
                call.resolve(FlightActivityCoordinator.shared.status())
            } catch { call.reject("Unable to update Live Activity; last known content retained") }
        }
    }
    @objc func start(_ call: CAPPluginCall) { sync(call) }
    @objc func update(_ call: CAPPluginCall) { sync(call) }
    @objc func end(_ call: CAPPluginCall) { Task { @MainActor in await FlightActivityCoordinator.shared.end(); call.resolve(FlightActivityCoordinator.shared.status()) } }
}
