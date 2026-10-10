import ActivityKit
import Capacitor
import Foundation

@objc(FlightLiveActivityPlugin)
public class FlightLiveActivityPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "FlightLiveActivityPlugin"
    public let jsName = "FlightLiveActivity"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "status", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "update", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "end", returnType: CAPPluginReturnPromise)
    ]

    private func current() -> Activity<FlightActivityAttributes>? {
        Activity<FlightActivityAttributes>.activities.first {
            $0.activityState == .active || $0.activityState == .stale
        }
    }

    private func result() -> [String: Any] {
        let activity = current()
        return ["supported": true, "enabled": ActivityAuthorizationInfo().areActivitiesEnabled,
                "active": activity != nil, "journeyKey": activity?.attributes.journeyKey ?? ""]
    }

    private func expireActivities() async {
        for activity in Activity<FlightActivityAttributes>.activities where activity.content.state.expiresAt <= Date() {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }

    @objc func status(_ call: CAPPluginCall) {
        Task { @MainActor in
            await expireActivities()
            call.resolve(result())
        }
    }

    private func content(_ call: CAPPluginCall) throws -> ActivityContent<FlightActivityAttributes.ContentState> {
        guard let expiry = call.getDouble("expiresAt"), let staleAt = call.getDouble("staleAt"),
              expiry.isFinite, staleAt.isFinite,
              let flight = call.getString("flightNumber"), !flight.isEmpty,
              let step = call.getString("nextStep"), !step.isEmpty else {
            throw NSError(domain: "FlightActivity", code: 1)
        }
        let expiryDate = Date(timeIntervalSince1970: expiry / 1000)
        guard expiryDate > Date() else { throw NSError(domain: "FlightActivity", code: 2) }
        func text(_ name: String, _ limit: Int = 120) -> String { String((call.getString(name) ?? "").prefix(limit)) }
        let state = FlightActivityAttributes.ContentState(
            flightNumber: String(flight.prefix(16)), gate: text("gate", 24), boardingTime: text("boardingTime", 32),
            nextStep: String(step.prefix(180)), updatedText: text("updatedText"), stale: call.getBool("stale") ?? true,
            expiresAt: min(expiryDate, Date().addingTimeInterval(8 * 60 * 60)),
            gateLabel: text("gateLabel"), boardingLabel: text("boardingLabel"), updatedLabel: text("updatedLabel"),
            staleLabel: text("staleLabel"), missingLabel: text("missingLabel"))
        return ActivityContent(state: state, staleDate: min(Date(timeIntervalSince1970: staleAt / 1000), state.expiresAt))
    }

    @objc func start(_ call: CAPPluginCall) {
        Task { @MainActor in
            guard ActivityAuthorizationInfo().areActivitiesEnabled,
                  let key = call.getString("journeyKey"), !key.isEmpty, key.count < 512 else {
                call.reject("Live Activities are unavailable"); return
            }
            do {
                let data = try content(call)
                await expireActivities()
                if let existing = current(), existing.attributes.journeyKey == key {
                    await existing.update(data)
                } else {
                    for activity in Activity<FlightActivityAttributes>.activities {
                        await activity.end(nil, dismissalPolicy: .immediate)
                    }
                    _ = try Activity.request(attributes: FlightActivityAttributes(journeyKey: key), content: data, pushType: nil)
                }
                call.resolve(result())
            } catch { call.reject("Unable to start Live Activity") }
        }
    }

    @objc func update(_ call: CAPPluginCall) {
        Task { @MainActor in
            await expireActivities()
            guard let activity = current() else { call.resolve(result()); return }
            guard call.getString("journeyKey") == activity.attributes.journeyKey else {
                await activity.end(nil, dismissalPolicy: .immediate)
                call.resolve(result()); return
            }
            do { await activity.update(try content(call)); call.resolve(result()) }
            catch { await activity.end(nil, dismissalPolicy: .immediate); call.resolve(result()) }
        }
    }

    @objc func end(_ call: CAPPluginCall) {
        Task { @MainActor in
            for activity in Activity<FlightActivityAttributes>.activities {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
            call.resolve(result())
        }
    }
}
