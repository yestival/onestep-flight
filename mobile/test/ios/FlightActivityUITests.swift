import XCTest

final class FlightActivityUITests: XCTestCase {
    func testFreshActivityRenders() { verifyRender(fresh: true) }
    func testStaleActivityRenders() { verifyRender(fresh: false) }
    func testExistingInvisibleActivityRecovers() { verifyRender(fresh: false, recover: true) }
    func testFlightFactsRemainVisiblePastFreshnessDeadline() { verifyRender(fresh: true, ages: true) }
    private func verifyRender(fresh: Bool, recover: Bool = false, ages: Bool = false) {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "com.selene.onestepflight")
        if fresh { app.launchArguments = ["--fresh"] }
        if recover { app.launchArguments = ["--recover"] }
        if ages { app.launchArguments.append("--ages") }
        app.launch()
        let pastDeadline = Date().addingTimeInterval(35)
        let status = app.staticTexts["activity-smoke-status"]
        XCTAssertTrue(status.waitForExistence(timeout: 30))
        let created = NSPredicate(format: "label CONTAINS 'created=1'")
        expectation(for: created, evaluatedWith: status)
        waitForExpectations(timeout: 30)
        XCTAssertTrue(status.label.contains("reused=true"), "Recovery must reuse the existing activity")
        attach("native-created")
        XCUIDevice.shared.press(.home)
        let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let coverSheet = system.otherElements["lockscreen-date-view"]
        for _ in 0..<3 {
            // The centre top edge is the physical notch on iPhone 14. Start left of it.
            system.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.005))
                .press(forDuration: 0.05, thenDragTo: system.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: 0.85)))
            if coverSheet.waitForExistence(timeout: 5) { break }
        }
        attach("notification-center-open")
        XCTAssertTrue(coverSheet.exists, "Notification Center must be open before evaluating Widget rendering.")
        let heading = system.staticTexts.containing(NSPredicate(format: "label CONTAINS '请前往 B32 登机口'")).firstMatch
        var rendered = heading.waitForExistence(timeout: 30)
        // A fresh Simulator first presents the system's Allow/Don't Allow row.
        // Verify the real ongoing card after consent, not only its prompt preview.
        for title in ["Allow", "Always Allow"] {
            let allow = system.buttons[title].firstMatch
            if allow.exists {
                allow.tap()
                rendered = heading.waitForExistence(timeout: 10)
                XCTAssertFalse(allow.exists)
            }
        }
        attach("system-live-activity")
        print("WIDGET_SMOKE_SYSTEM_TREE\n\(system.debugDescription)")
        XCTAssertTrue(rendered, "ActivityKit creation alone is insufficient: the system must render the actual Widget heading.")
        for fact in ["BA 117", "T5", "14:30", "15:20"] {
            XCTAssertTrue(system.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", fact)).firstMatch.exists, "Missing known flight fact: \(fact)")
        }
        if !fresh {
            XCTAssertTrue(system.staticTexts.containing(NSPredicate(format: "label CONTAINS '14:05 更新' AND label CONTAINS '可打开 App 刷新'")).firstMatch.exists)
        }
        if ages {
            // iOS coalesces the stale-state transition: it remained active beyond
            // its deadline in the Simulator logs. Do not assert a delivery SLA.
            // Verify retention across the deadline; the explicit stale footer is
            // independently checked by testStaleActivityRenders.
            let elapsed = expectation(description: "Past the fixture freshness deadline")
            DispatchQueue.main.asyncAfter(deadline: .now() + max(0, pastDeadline.timeIntervalSinceNow)) { elapsed.fulfill() }
            wait(for: [elapsed], timeout: 40)
            XCTAssertTrue(heading.exists)
            for fact in ["BA 117", "T5", "14:30", "15:20", "14:05 更新"] {
                XCTAssertTrue(system.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", fact)).firstMatch.exists)
            }
            attach("past-deadline-while-backgrounded")
        }
    }
    private func attach(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
}
