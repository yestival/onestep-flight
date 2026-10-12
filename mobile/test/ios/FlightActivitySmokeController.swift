// Appended to SceneDelegate only by widget-smoke.mjs in its disposable CI checkout.
// Uses the production coordinator, actual ActivityKit and the embedded production Widget.
import ActivityKit

final class FlightActivitySmokeController: UIViewController {
    private let status = UILabel()
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        status.numberOfLines = 0
        status.accessibilityIdentifier = "activity-smoke-status"
        status.frame = CGRect(x: 20, y: 100, width: 340, height: 240)
        view.addSubview(status)
    }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        Task { @MainActor in
            await FlightActivityCoordinator.shared.end()
            var state = FlightDisplayState()
            let now = Int64(Date().timeIntervalSince1970)
            state.journeyKey = "smoke-BA117-LHR-JFK"
            state.flightNumber = "BA 117"
            state.flightDate = "2026-10-12"; state.dateText = "10月12日"
            state.departureAirport = "LHR"; state.arrivalAirport = "JFK"
            state.actionCode = "GO_TO_GATE"; state.nextStep = "请前往 B32 登机口"
            state.headingIncludesGate = true
            state.terminal = "T5"; state.gate = "B32"
            state.boardingTime = "14:30"; state.departureTime = "15:20"
            state.departureTimeType = "ESTIMATED"
            state.metricOneLabel = "航站楼"; state.metricOneValue = "T5"
            state.metricTwoLabel = "登机时间"; state.metricTwoValue = "14:30"
            state.metricThreeLabel = "预计起飞"; state.metricThreeValue = "15:20"
            state.timezoneText = "时间为出发机场当地时间"
            state.updatedText = "14:05 更新"; state.locale = "zh"
            state.staleLabel = "可打开 App 刷新"
            state.updatedAt = now - 3600; state.staleAt = now - 3000
            if ProcessInfo.processInfo.arguments.contains("--fresh") {
                state.stale = false; state.updatedAt = now; state.staleAt = now + 600
            }
            if ProcessInfo.processInfo.arguments.contains("--ages") { state.staleAt = now + 25 }
            state.journeyEndsAt = now + 86400
            do {
                var legacyID: String?
                if ProcessInfo.processInfo.arguments.contains("--recover") {
                    let legacy = try Activity<FlightActivityAttributes>.request(
                        attributes: FlightActivityAttributes(journeyKey: state.journeyKey),
                        content: ActivityContent(state: state, staleDate: Date(timeIntervalSince1970: TimeInterval(state.staleAt))),
                        pushType: .token)
                    legacyID = legacy.id
                    NSLog("WIDGET_SMOKE legacy id=\(legacy.id) state=\(legacy.activityState) staleDate=\(String(describing: legacy.content.staleDate))")
                }
                try await FlightActivityCoordinator.shared.sync(state)
                try await FlightActivityCoordinator.shared.sync(state)
                let activities = Activity<FlightActivityAttributes>.activities
                let reused = legacyID == nil || legacyID == activities.first?.id
                status.text = "created=\(activities.count) reused=\(reused) state=\(String(describing: activities.first?.activityState))"
                NSLog("WIDGET_SMOKE \(status.text ?? "")")
                for activity in activities {
                    NSLog("WIDGET_SMOKE synced id=\(activity.id) state=\(activity.activityState) staleDate=\(String(describing: activity.content.staleDate))")
                }
            } catch { status.text = "error=\(error)"; print("WIDGET_SMOKE \(error)") }
        }
    }
}
