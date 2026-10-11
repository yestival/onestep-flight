import Foundation

@main struct ActivityLifecycleTests {
    @MainActor static func main() async throws {
        let suite = "flight-activity-test-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let coordinator = FlightActivityCoordinator(defaults: defaults)
        let store = ActivityStore.shared
        var flight = FlightDisplayState()
        flight.journeyKey = "BA117|2026-10-12|LHR|JFK"
        flight.flightNumber = "BA 117"; flight.gate = "B24"; flight.nextStep = "Go to gate B24"
        flight.journeyEndsAt = Int64(Date().timeIntervalSince1970) + 86400
        flight.staleAt = Int64(Date().timeIntervalSince1970) - 600
        flight.stale = true
        async let first: Void = coordinator.sync(flight)
        async let second: Void = coordinator.sync(flight)
        _ = try await (first, second)
        precondition(store.requests == 1, "Concurrent automatic sync must create only one activity")
        precondition(coordinator.current?.content.state.gate == "B24", "Stale state must preserve known facts")

        // This is the regression: an old subscription clear arrives after the
        // new card was created. It must not end the card or erase its retry state.
        await coordinator.end(journeyKey: "previous-flight")
        await coordinator.resume()
        precondition(store.requests == 1 && coordinator.current != nil, "Old journey cleanup ended the new activity")
        var next = flight; next.journeyKey = "BA117|2026-10-13|LHR|JFK"
        try await coordinator.sync(next)
        await coordinator.end(journeyKey: flight.journeyKey)
        coordinator.current?.activityState = .ended
        await coordinator.resume()
        precondition(coordinator.current?.attributes.journeyKey == next.journeyKey, "Old cleanup erased the new persisted display")

        coordinator.current?.activityState = .dismissed
        try await coordinator.sync(next)
        let dismissedCount = store.requests
        await coordinator.resume()
        precondition(coordinator.current == nil && store.requests == dismissedCount, "User dismissal must not be undone")
        next.journeyKey = "BA117|2026-10-14|LHR|JFK"
        try await coordinator.sync(next)
        precondition(coordinator.current != nil, "A new flight must not inherit dismissal")
        await coordinator.end(journeyKey: next.journeyKey)
        let clearedCount = store.requests
        await coordinator.resume()
        precondition(store.requests == clearedCount, "Foreground recovery recreated a cleared flight")

        UIApplication.shared.applicationState = .background
        try await coordinator.sync(flight)
        precondition(store.requests == clearedCount, "Background work must not create an activity")
        UIApplication.shared.applicationState = .active
        store.rejectRequest = true
        await coordinator.resume()
        precondition(coordinator.status()["errorReference"] as? String == "ActivityKitTest:7")
        store.rejectRequest = false
        await coordinator.resume()
        precondition(coordinator.current?.attributes.journeyKey == flight.journeyKey, "Creation failure lost the cached flight")
        precondition(coordinator.status()["errorCode"] as? String == "")
        print("Native automatic creation, stale retention, scoped cleanup, dismissal and foreground recovery verified")
    }
}
