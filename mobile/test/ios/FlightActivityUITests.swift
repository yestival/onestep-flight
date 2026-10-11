import XCTest

final class FlightActivityUITests: XCTestCase {
    func testFreshActivityRenders() { verifyRender(fresh: true) }
    func testStaleActivityRenders() { verifyRender(fresh: false) }
    private func verifyRender(fresh: Bool) {
        continueAfterFailure = false
        let app = XCUIApplication(bundleIdentifier: "com.selene.onestepflight")
        if fresh { app.launchArguments = ["--fresh"] }
        app.launch()
        let status = app.staticTexts["activity-smoke-status"]
        XCTAssertTrue(status.waitForExistence(timeout: 30))
        let created = NSPredicate(format: "label CONTAINS 'created=1'")
        expectation(for: created, evaluatedWith: status)
        waitForExpectations(timeout: 30)
        attach("native-created")
        XCUIDevice.shared.press(.home)
        let system = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        system.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.01))
            .press(forDuration: 0.2, thenDragTo: system.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8)))
        let heading = system.staticTexts.containing(NSPredicate(format: "label CONTAINS '请前往 B32 登机口'")).firstMatch
        let rendered = heading.waitForExistence(timeout: 30)
        attach("system-live-activity")
        print("WIDGET_SMOKE_SYSTEM_TREE\n\(system.debugDescription)")
        XCTAssertTrue(rendered, "ActivityKit creation alone is insufficient: the system must render the actual Widget heading.")
    }
    private func attach(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
}
