import Foundation

let fixture = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))) as! [String: Any]
let expected = fixture["expected"] as! [String: Any]
let data = try JSONSerialization.data(withJSONObject: expected)
let state = try JSONDecoder().decode(FlightDisplayState.self, from: data)
precondition(state.updatedAt == 1_791_637_500)
precondition(state.gate == "B32" && state.headingIncludesGate)
let encoded = try JSONSerialization.jsonObject(with: JSONEncoder().encode(state)) as! NSDictionary
precondition(encoded.isEqual(to: expected), "Swift encoding must match the Java/JS fixture exactly")
let english = state.localized(to: "en")
precondition(english.nextStep == "Go to gate B32" && english.metricThreeLabel == "Estimated departure")
precondition(english.snapshotVersion == state.snapshotVersion && english.updatedAt == state.updatedAt)
let spanish = english.localized(to: "es")
precondition(spanish.nextStep == "Ve a la puerta B32" && spanish.metricOneValue == "T5")
let cachedGate = state.applying(progress: ["checkedIn": true, "hasCheckedBaggage": false, "securityCleared": true, "atGate": true], revision: 4, gateAtConfirmation: "B24", terminalAtConfirmation: "T5", language: "en")
precondition(cachedGate.nextStep == "Go to gate B32" && cachedGate.gate == "B32")
precondition(cachedGate.updatedAt == state.updatedAt && cachedGate.snapshotVersion == state.snapshotVersion)
let security = state.applying(progress: ["checkedIn": true, "hasCheckedBaggage": false, "securityCleared": false], revision: 4, gateAtConfirmation: "B32", terminalAtConfirmation: "T5", language: "zh")
precondition(security.nextStep == "请前往安检" && security.metricOneValue == "T5 · B32")
let legacy = try JSONDecoder().decode(FlightDisplayState.self, from: Data("{\"flightNumber\":\"BA 117\",\"gate\":\"B24\",\"expiresAt\":813330000}".utf8))
precondition(legacy.journeyEndsAt == 813330000 + 978307200)
precondition(legacy.metricOneValue == "B24")
precondition(legacy.snapshotVersion == 0)
let withFacts = try JSONDecoder().decode(FlightDisplayState.self, from: Data("{\"gate\":\"B40\",\"facts\":{\"gate\":\"B40\",\"boardingTime\":\"2026-09-05T11:20:00+02:00\"}}".utf8))
precondition(withFacts.facts?["gate"] == "B40")
let recovered = try JSONDecoder().decode(FlightDisplayState.self, from: JSONEncoder().encode(withFacts))
precondition(recovered.facts == withFacts.facts)
print("Swift shared-state Unix seconds, legacy reference epoch and missing fields verified")
