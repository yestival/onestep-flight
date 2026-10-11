import Foundation

// Only the operating-system boundary is doubled. The tests compile and execute
// the production coordinator, including its persistence and async operation queue.
protocol ActivityAttributes { associatedtype ContentState: Codable, Hashable }
struct FlightActivityAttributes: ActivityAttributes {
    typealias ContentState = FlightDisplayState
    var journeyKey: String
}
enum ActivityState: Equatable { case active, stale, ended, dismissed }
enum ActivityUIDismissalPolicy { case immediate, after(Date) }
enum PushType { case token }
struct ActivityContent<State> { var state: State; var staleDate: Date? }
struct ActivityAuthorizationInfo { var areActivitiesEnabled: Bool { true } }

@MainActor final class ActivityStore {
    static let shared = ActivityStore()
    var activities: [Activity<FlightActivityAttributes>] = []
    var requests = 0
    var rejectRequest = false
}
@MainActor final class Activity<Attributes: ActivityAttributes> {
    static var activities: [Activity<Attributes>] { ActivityStore.shared.activities as! [Activity<Attributes>] }
    let id = UUID().uuidString
    let attributes: Attributes
    var content: ActivityContent<Attributes.ContentState>
    var activityState = ActivityState.active
    var pushToken: Data? { nil }
    var pushTokenUpdates: AsyncStream<Data> { AsyncStream { $0.finish() } }
    var activityStateUpdates: AsyncStream<ActivityState> { AsyncStream { $0.finish() } }
    var contentUpdates: AsyncStream<ActivityContent<Attributes.ContentState>> { AsyncStream { $0.finish() } }
    init(attributes: Attributes, content: ActivityContent<Attributes.ContentState>) {
        self.attributes = attributes; self.content = content
    }
    static func request(attributes: Attributes, content: ActivityContent<Attributes.ContentState>, pushType: PushType?) throws -> Activity<Attributes> {
        let store = ActivityStore.shared
        if store.rejectRequest { throw NSError(domain: "ActivityKitTest", code: 7) }
        let result = Activity<Attributes>(attributes: attributes, content: content)
        store.requests += 1
        store.activities.append(result as! Activity<FlightActivityAttributes>)
        return result
    }
    func update(_ value: ActivityContent<Attributes.ContentState>) async { content = value }
    func end(_ value: ActivityContent<Attributes.ContentState>?, dismissalPolicy: ActivityUIDismissalPolicy) async {
        if let value { content = value }
        activityState = .ended
    }
}
@MainActor final class UIApplication {
    static let shared = UIApplication()
    enum State: Equatable { case active, inactive, background }
    var applicationState = State.active
}
@MainActor final class FlightPushCoordinator {
    static let shared = FlightPushCoordinator()
    func preferredState(_ value: FlightDisplayState) -> FlightDisplayState { value }
    func acceptActivityState(_ value: FlightDisplayState) {}
    func bind(activity: String, journeyKey: String, token: String?, state: String) async {}
}
