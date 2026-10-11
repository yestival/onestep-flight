import Foundation

struct FlightDisplayState: Codable, Hashable {
        var journeyKey: String = ""
        var flightNumber: String = ""
        var flightDate: String = ""
        var departureAirport: String = ""
        var arrivalAirport: String = ""
        var dateText: String = ""
        var nextStep: String = ""
        var terminal: String = ""
        var gate: String = ""
        var boardingTime: String = ""
        var departureTime: String = ""
        var arrivalTime: String = ""
        var arrivalTerminal: String = ""
        var baggageBelt: String = ""
        var metricOneValue: String = ""
        var metricTwoValue: String = ""
        var metricThreeValue: String = ""
        var updatedText: String = ""
        var phase: String = "DEPARTURE"
        var actionCode: String = "WAIT"
        var departureTimeType: String = "SCHEDULED"
        var arrivalTimeType: String = "SCHEDULED"
        var locale: String = "en"
        var metricOneLabel: String = "Terminal / Gate"
        var metricTwoLabel: String = "Boarding"
        var metricThreeLabel: String = "Scheduled departure"
        var timezoneText: String = "Departure airport local time"
        var staleLabel: String = "Open app to refresh"
        var offlineLabel: String = "Refresh when online"
        var missingLabel: String = "To be announced"
        var headingIncludesGate: Bool = false
        var stale: Bool = true
        var offline: Bool = false
        var updatedAt: Int64 = 0
        var staleAt: Int64 = 0
        var journeyEndsAt: Int64 = 0
        var snapshotVersion: Int64 = 0
        var clientRevision: Int64 = 0
        var expiresAt: Date { Date(timeIntervalSince1970: TimeInterval(journeyEndsAt)) }
        init() {}
        func localized(to language: String, regenerate: Bool = false) -> Self {
            guard ["zh", "en", "es"].contains(language), language != locale || regenerate else { return self }
            var result = self
            func l(_ zh: String, _ en: String, _ es: String) -> String { language == "zh" ? zh : language == "es" ? es : en }
            let missing = l("待公布", "To be announced", "Por anunciar")
            func value(_ text: String) -> String {
                if text.isEmpty || text == missingLabel { return missing }
                if ["待确认", "To be confirmed", "Por confirmar"].contains(text) { return l("待确认", "To be confirmed", "Por confirmar") }
                return text
            }
            result.locale = language; result.missingLabel = missing
            result.terminal = value(terminal); result.gate = value(gate); result.boardingTime = value(boardingTime)
            result.departureTime = value(departureTime); result.arrivalTime = value(arrivalTime)
            result.arrivalTerminal = value(arrivalTerminal); result.baggageBelt = value(baggageBelt)
            switch actionCode {
            case "GO_TO_GATE": result.nextStep = headingIncludesGate ? l("请前往 \(result.gate) 登机口", "Go to gate \(result.gate)", "Ve a la puerta \(result.gate)") : l("请确认登机口", "Confirm your gate", "Confirma tu puerta")
            case "BOARD_NOW": result.nextStep = headingIncludesGate ? l("请在 \(result.gate) 登机口登机", "Board at gate \(result.gate)", "Embarca en la puerta \(result.gate)") : l("请确认登机口后登机", "Confirm the gate before boarding", "Confirma la puerta antes de embarcar")
            case "SECURITY": result.nextStep = l("请前往安检", "Go to security", "Ve al control de seguridad")
            case "CHECK_IN": result.nextStep = l("请办理值机", "Check in", "Ve a facturar")
            case "CHECK_IN_QUESTION": result.nextStep = l("请确认是否已值机", "Confirm your check-in status", "Confirma si has facturado")
            case "CONFIRM_FLIGHT": result.nextStep = l("请确认航班", "Confirm your flight", "Confirma tu vuelo")
            case "BAG_DROP": result.nextStep = l("请托运行李", "Drop off your baggage", "Entrega el equipaje")
            case "BAGGAGE_QUESTION": result.nextStep = l("请确认是否托运行李", "Confirm checked baggage", "Confirma el equipaje facturado")
            case "IN_FLIGHT": result.nextStep = l("飞行中，请留意机组指引", "In flight · Follow cabin crew guidance", "En vuelo · Sigue las indicaciones de la tripulación")
            case "BAGGAGE_CLAIM": result.nextStep = l("请前往行李提取区", "Go to baggage claim", "Ve a recoger el equipaje")
            case "EXIT_AIRPORT": result.nextStep = l("请前往机场出口", "Go to the airport exit", "Ve a la salida del aeropuerto")
            case "CANCELLED": result.nextStep = l("航班已取消，请联系航空公司", "Flight cancelled · Contact the airline", "Vuelo cancelado · Contacta con la aerolínea")
            case "ASK_FOR_HELP": result.nextStep = l("请联系工作人员", "Ask a member of staff", "Pide ayuda al personal")
            default: result.nextStep = l("请留意屏幕及广播", "Watch the screens and announcements", "Consulta las pantallas y escucha los avisos")
            }
            let departure = phase == "DEPARTURE"
            result.metricOneLabel = departure ? headingIncludesGate ? l("航站楼", "Terminal", "Terminal") : l("航站楼 / 登机口", "Terminal / Gate", "Terminal / Puerta") : l("到达航站楼", "Arrival terminal", "Terminal de llegada")
            result.metricOneValue = departure ? headingIncludesGate ? result.terminal : result.terminal + " · " + result.gate : result.arrivalTerminal
            result.metricTwoLabel = departure ? l("登机时间", "Boarding", "Embarque") : l("行李转盘", "Baggage belt", "Cinta de equipaje")
            result.metricTwoValue = departure ? result.boardingTime : result.baggageBelt
            result.metricThreeValue = departure ? result.departureTime : result.arrivalTime
            result.metricThreeLabel = departure ? departureTimeType == "ESTIMATED" ? l("预计起飞", "Estimated departure", "Salida estimada") : l("计划起飞", "Scheduled departure", "Salida prevista") : arrivalTimeType == "ESTIMATED" ? l("预计到达", "Estimated arrival", "Llegada estimada") : l("计划到达", "Scheduled arrival", "Llegada prevista")
            if actionCode == "CANCELLED" && departure { result.metricThreeLabel = departureTimeType == "ESTIMATED" ? l("原预计起飞", "Former estimated departure", "Anterior salida estimada") : l("原计划起飞", "Original scheduled departure", "Salida prevista original") }
            if actionCode == "CANCELLED" {
                if departure { result.metricTwoLabel = l("原登机时间", "Original boarding", "Embarque original") }
                else { result.metricThreeLabel = arrivalTimeType == "ESTIMATED" ? l("原预计到达", "Former estimated arrival", "Anterior llegada estimada") : l("原计划到达", "Original scheduled arrival", "Llegada prevista original") }
            }
            result.timezoneText = departure ? l("时间为出发机场当地时间", "Departure airport local time", "Hora local del aeropuerto de salida") : l("时间为目的地当地时间", "Destination local time", "Hora local del destino")
            result.staleLabel = l("可打开 App 刷新", "Open app to refresh", "Abre la app para actualizar")
            result.offlineLabel = l("联网后可刷新", "Refresh when online", "Actualiza al conectarte")
            result.updatedText = updatedAt > 0 ? String(updatedText.prefix(5)) + l(" 更新", " updated", " actualizado") : l("尚未核验", "Not verified", "Sin verificar")
            let date = DateFormatter(); date.locale = Locale(identifier: "en_US_POSIX"); date.timeZone = TimeZone(secondsFromGMT: 0); date.dateFormat = "yyyy-MM-dd"
            if let parsed = date.date(from: flightDate) { date.locale = Locale(identifier: language); date.dateFormat = language == "zh" ? "M月d日" : "MMM d"; result.dateText = date.string(from: parsed) }
            return result
        }
        func applying(progress: [String: Any], revision: Int64, gateAtConfirmation: String?, terminalAtConfirmation: String?, language: String) -> Self {
            guard revision > clientRevision, actionCode != "CANCELLED" else { return localized(to: language) }
            var result = self
            func done(_ name: String) -> Bool { progress[name] as? Bool ?? false }
            let baggage = progress["hasCheckedBaggage"] as? Bool
            let atGate = done("atGate") && gateAtConfirmation == gate && terminalAtConfirmation == terminal
            if done("landed") { result.phase = "ARRIVAL" }
            else if done("boarded") && phase == "DEPARTURE" { result.phase = "IN_FLIGHT" }
            if done("needsAssistance") { result.actionCode = "ASK_FOR_HELP" }
            else if result.phase != "DEPARTURE" {
                result.actionCode = !done("boarded") && !done("landed") ? "ASK_FOR_HELP" : result.phase == "IN_FLIGHT" ? "IN_FLIGHT" : baggage == true && !done("baggageCollected") ? "BAGGAGE_CLAIM" : "EXIT_AIRPORT"
            } else if !done("checkedIn") { result.actionCode = "CHECK_IN" }
            else if baggage == nil { result.actionCode = "BAGGAGE_QUESTION" }
            else if baggage == true && !done("bagDropped") { result.actionCode = "BAG_DROP" }
            else if !done("securityCleared") { result.actionCode = "SECURITY" }
            else if !atGate { result.actionCode = actionCode == "WAIT" ? "WAIT" : "GO_TO_GATE" }
            else { result.actionCode = actionCode == "BOARD_NOW" ? "BOARD_NOW" : "WAIT" }
            result.headingIncludesGate = ["GO_TO_GATE", "BOARD_NOW"].contains(result.actionCode) && !gate.isEmpty && gate != missingLabel && !["待确认", "To be confirmed", "Por confirmar"].contains(gate)
            result.clientRevision = revision
            return result.localized(to: language, regenerate: true)
        }
        enum CodingKeys: String, CodingKey {
            case journeyKey, flightNumber, flightDate, departureAirport, arrivalAirport, dateText, nextStep, terminal, gate, boardingTime, departureTime, arrivalTime, arrivalTerminal, baggageBelt, metricOneValue, metricTwoValue, metricThreeValue, updatedText, phase, actionCode, departureTimeType, arrivalTimeType, locale, metricOneLabel, metricTwoLabel, metricThreeLabel, timezoneText, staleLabel, offlineLabel, missingLabel, headingIncludesGate, stale, offline, updatedAt, staleAt, journeyEndsAt, snapshotVersion, clientRevision
        }
        private struct LegacyKey: CodingKey {
            var stringValue: String
            var intValue: Int? { nil }
            init?(stringValue: String) { self.stringValue = stringValue }
            init?(intValue: Int) { return nil }
        }
        init(from decoder: Decoder) throws {
            self.init()
            let c = try decoder.container(keyedBy: CodingKeys.self)
            journeyKey = try c.decodeIfPresent(String.self, forKey: .journeyKey) ?? journeyKey
            flightNumber = try c.decodeIfPresent(String.self, forKey: .flightNumber) ?? flightNumber
            flightDate = try c.decodeIfPresent(String.self, forKey: .flightDate) ?? flightDate
            departureAirport = try c.decodeIfPresent(String.self, forKey: .departureAirport) ?? departureAirport
            arrivalAirport = try c.decodeIfPresent(String.self, forKey: .arrivalAirport) ?? arrivalAirport
            dateText = try c.decodeIfPresent(String.self, forKey: .dateText) ?? dateText
            nextStep = try c.decodeIfPresent(String.self, forKey: .nextStep) ?? nextStep
            terminal = try c.decodeIfPresent(String.self, forKey: .terminal) ?? terminal
            gate = try c.decodeIfPresent(String.self, forKey: .gate) ?? gate
            boardingTime = try c.decodeIfPresent(String.self, forKey: .boardingTime) ?? boardingTime
            departureTime = try c.decodeIfPresent(String.self, forKey: .departureTime) ?? departureTime
            arrivalTime = try c.decodeIfPresent(String.self, forKey: .arrivalTime) ?? arrivalTime
            arrivalTerminal = try c.decodeIfPresent(String.self, forKey: .arrivalTerminal) ?? arrivalTerminal
            baggageBelt = try c.decodeIfPresent(String.self, forKey: .baggageBelt) ?? baggageBelt
            metricOneValue = try c.decodeIfPresent(String.self, forKey: .metricOneValue) ?? metricOneValue
            metricTwoValue = try c.decodeIfPresent(String.self, forKey: .metricTwoValue) ?? metricTwoValue
            metricThreeValue = try c.decodeIfPresent(String.self, forKey: .metricThreeValue) ?? metricThreeValue
            updatedText = try c.decodeIfPresent(String.self, forKey: .updatedText) ?? updatedText
            phase = try c.decodeIfPresent(String.self, forKey: .phase) ?? phase
            actionCode = try c.decodeIfPresent(String.self, forKey: .actionCode) ?? actionCode
            departureTimeType = try c.decodeIfPresent(String.self, forKey: .departureTimeType) ?? departureTimeType
            arrivalTimeType = try c.decodeIfPresent(String.self, forKey: .arrivalTimeType) ?? arrivalTimeType
            locale = try c.decodeIfPresent(String.self, forKey: .locale) ?? locale
            metricOneLabel = try c.decodeIfPresent(String.self, forKey: .metricOneLabel) ?? metricOneLabel
            metricTwoLabel = try c.decodeIfPresent(String.self, forKey: .metricTwoLabel) ?? metricTwoLabel
            metricThreeLabel = try c.decodeIfPresent(String.self, forKey: .metricThreeLabel) ?? metricThreeLabel
            timezoneText = try c.decodeIfPresent(String.self, forKey: .timezoneText) ?? timezoneText
            staleLabel = try c.decodeIfPresent(String.self, forKey: .staleLabel) ?? staleLabel
            offlineLabel = try c.decodeIfPresent(String.self, forKey: .offlineLabel) ?? offlineLabel
            missingLabel = try c.decodeIfPresent(String.self, forKey: .missingLabel) ?? missingLabel
            headingIncludesGate = try c.decodeIfPresent(Bool.self, forKey: .headingIncludesGate) ?? headingIncludesGate
            stale = try c.decodeIfPresent(Bool.self, forKey: .stale) ?? stale
            offline = try c.decodeIfPresent(Bool.self, forKey: .offline) ?? offline
            updatedAt = try c.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0
            staleAt = try c.decodeIfPresent(Int64.self, forKey: .staleAt) ?? 0
            journeyEndsAt = try c.decodeIfPresent(Int64.self, forKey: .journeyEndsAt) ?? 0
            snapshotVersion = try c.decodeIfPresent(Int64.self, forKey: .snapshotVersion) ?? 0
            clientRevision = try c.decodeIfPresent(Int64.self, forKey: .clientRevision) ?? 0
            if journeyEndsAt == 0 {
                let legacy = try decoder.container(keyedBy: LegacyKey.self)
                if let value = try legacy.decodeIfPresent(Double.self, forKey: LegacyKey(stringValue: "expiresAt")!) {
                    journeyEndsAt = Int64(value > 100_000_000_000 ? value / 1000 : value < 1_000_000_000 ? value + 978_307_200 : value)
                }
            }
            if metricOneValue.isEmpty { metricOneValue = gate }
            if metricTwoValue.isEmpty { metricTwoValue = boardingTime }
        }
}

#if canImport(ActivityKit) && !FLIGHT_STATE_TEST
import ActivityKit
struct FlightActivityAttributes: ActivityAttributes {
    typealias ContentState = FlightDisplayState
    var journeyKey: String
}
#endif
