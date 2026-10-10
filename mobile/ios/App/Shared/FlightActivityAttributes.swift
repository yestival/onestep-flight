import ActivityKit
import Foundation

struct FlightActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var flightNumber: String
        var gate: String
        var boardingTime: String
        var nextStep: String
        var updatedText: String
        var stale: Bool
        var expiresAt: Date
        var gateLabel: String
        var boardingLabel: String
        var updatedLabel: String
        var staleLabel: String
        var missingLabel: String
    }
    var journeyKey: String
}
