import XCTest
import UIKit

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
        var rendered = heading.waitForExistence(timeout: 10)
        // iOS 26 can initially place the activity list above the visible clock
        // while its first archive is loading. Reveal the notification list as a
        // user would, rather than accepting an offscreen AX node as a render.
        if !rendered {
            system.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.78))
                .press(forDuration: 0.05, thenDragTo: system.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.38)))
            rendered = heading.waitForExistence(timeout: 20)
        }
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
        assertReadableHeading(heading, system: system)
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
    private func assertReadableHeading(_ heading: XCUIElement, system: XCUIApplication) {
        // AX text can exist even when a broken archive paints white on white.
        // Check actual pixels in the heading, after the system consent UI closes.
        guard let image = XCUIScreen.main.screenshot().image.cgImage else { XCTFail("Missing screenshot"); return }
        let scale = CGFloat(image.width) / system.frame.width
        let frame = heading.frame
        let region = CGRect(x: frame.minX * scale, y: frame.minY * scale,
                            width: frame.width * scale, height: frame.height * scale).integral
        guard let crop = image.cropping(to: region) else { XCTFail("Heading outside screenshot"); return }
        var pixels = [UInt8](repeating: 0, count: crop.width * crop.height * 4)
        pixels.withUnsafeMutableBytes { bytes in
            let context = CGContext(data: bytes.baseAddress, width: crop.width, height: crop.height,
                                    bitsPerComponent: 8, bytesPerRow: crop.width * 4,
                                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            context.draw(crop, in: CGRect(x: 0, y: 0, width: crop.width, height: crop.height))
        }
        func linear(_ byte: UInt8) -> Double {
            let c = Double(byte) / 255
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        var darkest = 1.0, lightest = 0.0
        for offset in stride(from: 0, to: pixels.count, by: 4) {
            let light = 0.2126 * linear(pixels[offset]) + 0.7152 * linear(pixels[offset + 1]) + 0.0722 * linear(pixels[offset + 2])
            darkest = min(darkest, light); lightest = max(lightest, light)
        }
        XCTAssertGreaterThanOrEqual((lightest + 0.05) / (darkest + 0.05), 4.5, "Visible heading pixels must contrast with the actual card background")
    }
    private func attach(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
}
