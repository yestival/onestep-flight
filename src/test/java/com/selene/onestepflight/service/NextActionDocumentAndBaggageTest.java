package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.ActionCode;
import com.selene.onestepflight.dto.JourneyContext;
import com.selene.onestepflight.dto.JourneyStepType;
import com.selene.onestepflight.dto.NextActionResponse;
import com.selene.onestepflight.dto.ResolvedFlightContext;

class NextActionDocumentAndBaggageTest {

	private final NextActionService service = new NextActionService();

	@Test
	void onlyTrustedBoardingPassSignalSkipsCheckIn() {
		ResolvedFlightContext flight = flight("D 20-24", null);

		for (String type : List.of("BOOKING_CONFIRMATION", "E_TICKET", "ITINERARY", "UNKNOWN")) {
			NextActionResponse result = service.calculate(flight,
					journey(flight, type, true, false, null, false, false, false, false));
			assertThat(result.actionCode()).as(type).isEqualTo(ActionCode.CHECK_IN);
		}

		NextActionResponse boardingPass = service.calculate(flight,
				journey(flight, "BOARDING_PASS", true, false, null, false, false, false, false));
		assertThat(boardingPass.stepType()).isEqualTo(JourneyStepType.BAGGAGE_QUESTION);
		assertThat(boardingPass.actionCode()).isNull();
	}

	@Test
	void arrivalBaggageBeltNeverBecomesDepartureBagDropDestination() {
		ResolvedFlightContext flight = flight("D 20-24", "7");

		NextActionResponse departure = service.calculate(flight,
				journey(flight, "MANUAL_ENTRY", false, true, true, false, false, false, false));
		assertThat(departure.actionCode()).isEqualTo(ActionCode.BAG_DROP);
		assertThat(departure.destination()).isEqualTo("D 20-24");
		assertThat(departure.destination()).doesNotContain("7");

		NextActionResponse arrival = service.calculate(flight,
				journey(flight, "MANUAL_ENTRY", false, true, true, true, true, true, false));
		assertThat(arrival.actionCode()).isEqualTo(ActionCode.BAGGAGE_CLAIM);
		assertThat(arrival.destination()).contains("7");
		assertThat(arrival.destination()).doesNotContain("20-24");
	}

	private JourneyContext journey(ResolvedFlightContext flight, String documentType,
			boolean boardingPassDetected, boolean checkedIn, Boolean hasCheckedBaggage,
			boolean bagDropped, boolean boarded, boolean landed, boolean baggageCollected) {
		return new JourneyContext("BA117", LocalDate.of(2026, 8, 24), flight, false, null,
				documentType, boardingPassDetected, checkedIn, hasCheckedBaggage, bagDropped,
				false, false, boarded, landed, baggageCollected, false, false,
				null, null, true, false);
	}

	private ResolvedFlightContext flight(String dedicatedBagDropDesk, String arrivalBaggageBelt) {
		Instant timestamp = Instant.parse("2026-08-24T01:00:00Z");
		return new ResolvedFlightContext(
				"BA117", "BA117", LocalDate.of(2026, 8, 24), "MANUAL_ENTRY", false,
				"LHR", "London Heathrow", "JFK", "New York JFK",
				null, null, null, null, null, null, null, null, null,
				null, null, "5", "B42", null, "D", "D", dedicatedBagDropDesk,
				arrivalBaggageBelt, "EXPECTED", 0, 0,
				timestamp, timestamp, timestamp, timestamp,
				null, "AERODATABOX", null, null, true, false, null,
				false, false, Map.of(), Map.of(), Map.of(), List.of(), false, List.of(), null);
	}
}
