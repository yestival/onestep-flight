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
        precondition(store.updates == 0, "Identical automatic sync must not issue a duplicate system update")
        precondition(coordinator.current?.content.state.gate == "B24", "Stale state must preserve known facts")
        precondition(coordinator.current?.content.staleDate == nil, "An elapsed initial system deadline suppresses the Lock Screen")
        precondition(coordinator.current?.content.state.staleAt == flight.staleAt, "Do not invent a new freshness deadline")
        let existingID = coordinator.current?.id
        // The old implementation can leave an elapsed system deadline behind.
        coordinator.current?.content.staleDate = Date(timeIntervalSince1970: TimeInterval(flight.staleAt))
        defaults.removeObject(forKey: "flight.activityDeadlinePolicy." + existingID!)
        let originalEnd = defaults.double(forKey: "flight.activityNormalEnd." + existingID!)
        flight.stale = false // A cached state may only carry its original deadline.
        try await coordinator.sync(flight)
        precondition(store.requests == 2 && coordinator.current?.content.staleDate == nil, "An invisible legacy card needs a new system activity")
        precondition(coordinator.current?.id != existingID, "Updating the legacy ID alone does not reliably restore its system view")
        precondition(defaults.double(forKey: "flight.activityNormalEnd." + coordinator.current!.id) == originalEnd, "Repair must preserve the original activity lifetime")
        precondition(!coordinator.acceptsBinding(activity: existingID!, journeyKey: flight.journeyKey, state: "active"), "Late legacy tokens must not replace the new subscription")
        precondition(!coordinator.acceptsBinding(activity: existingID!, journeyKey: flight.journeyKey, state: "ended"), "Late legacy removal must not revoke the replacement")
        precondition(coordinator.acceptsBinding(activity: coordinator.current!.id, journeyKey: flight.journeyKey, state: "active"))
        let repairedID = coordinator.current?.id
        try await coordinator.sync(flight)
        precondition(store.requests == 2 && store.updates == 0, "Repeated recovery must reuse the repaired card")
        precondition(coordinator.current?.content.state.stale == true, "Elapsed data must still retain its stale flag")
        precondition(coordinator.current?.id == repairedID, "A repaired activity must not be recreated again")
        var fresh = flight; fresh.staleAt = Int64(Date().timeIntervalSince1970) + 600
        try await coordinator.sync(fresh)
        precondition(coordinator.current?.content.staleDate != nil, "Fresh data still needs the future system stale transition")
        coordinator.current?.content.staleDate = Date().addingTimeInterval(-1)
        try await coordinator.sync(flight)
        precondition(coordinator.current?.id == repairedID && store.requests == 2, "A normally aging card must only update, never be replaced")

        // This is the regression: an old subscription clear arrives after the
        // new card was created. It must not end the card or erase its retry state.
        await coordinator.end(journeyKey: "previous-flight")
        await coordinator.resume()
        precondition(store.requests == 2 && coordinator.current != nil, "Old journey cleanup ended the new activity")
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
        await coordinator.end()

        // A legacy card may first be discovered by a background fetch. Updating
        // it must preserve a durable repair marker until creation is permitted.
        let legacy = try Activity<FlightActivityAttributes>.request(attributes: FlightActivityAttributes(journeyKey: flight.journeyKey),
            content: ActivityContent(state: flight, staleDate: Date(timeIntervalSince1970: TimeInterval(flight.staleAt))), pushType: .token)
        let legacyCount = store.requests
        UIApplication.shared.applicationState = .background
        try await coordinator.sync(flight)
        precondition(store.requests == legacyCount && coordinator.current?.id == legacy.id, "Background recovery must not request a replacement")
        precondition(legacy.content.staleDate == nil, "Background updates must preserve useful state without an elapsed system deadline")
        UIApplication.shared.applicationState = .active
        store.rejectRequest = true
        do { try await coordinator.sync(flight); preconditionFailure("Expected native request failure") }
        catch { precondition(coordinator.current?.id == legacy.id, "A failed replacement must leave the old activity intact") }
        store.rejectRequest = false
        await coordinator.resume()
        precondition(coordinator.current?.id != legacy.id && store.requests == legacyCount + 1, "Foreground retry must repair the legacy activity once")
        await coordinator.end()

        // Do not replace legacy activities after their recorded system lifetime.
        let expired = try Activity<FlightActivityAttributes>.request(attributes: FlightActivityAttributes(journeyKey: flight.journeyKey),
            content: ActivityContent(state: flight, staleDate: Date(timeIntervalSince1970: TimeInterval(flight.staleAt))), pushType: .token)
        defaults.set(Date().timeIntervalSince1970 - 1, forKey: "flight.activityNormalEnd." + expired.id)
        let expiredCount = store.requests
        try await coordinator.sync(flight)
        precondition(store.requests == expiredCount, "Repair must not restart the ActivityKit lifetime")
        expired.activityState = .dismissed
        await coordinator.end()
        print("Native creation, one-time legacy repair, lifetime preservation, late bindings, dismissal and foreground retry verified")
    }
}
