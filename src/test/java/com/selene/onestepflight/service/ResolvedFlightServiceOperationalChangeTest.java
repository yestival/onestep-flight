package com.selene.onestepflight.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.FlightChange;
import com.selene.onestepflight.dto.FlightResponse;
import com.selene.onestepflight.dto.FlightTimeContext;
import com.selene.onestepflight.dto.ResolvedFlightContext;

class ResolvedFlightServiceOperationalChangeTest {
	private static final Instant DETECTED_AT = Instant.parse("2026-08-27T00:00:00Z");
	private static final LocalDate DATE = LocalDate.of(2026, 8, 27);
	private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-08-27T09:00:00+08:00");
	private static final OffsetDateTime ARRIVAL = OffsetDateTime.parse("2026-08-27T11:00:00+08:00");
	private static final Set<String> LOCATION_TYPES = Set.of(
			"TERMINAL_CHANGED", "GATE_CHANGED", "CHECK_IN_COUNTER_CHANGED",
			"BAG_DROP_DESK_CHANGED", "BAGGAGE_BELT_CHANGED");

	private final ResolvedFlightService service = new ResolvedFlightService(null, null, null, null);

	@Test
	void detectsAllSupportedOperationalChangesInOneRefresh() {
		List<FlightChange> changes = detect(snapshot(false));

		assertEquals(Set.of(
				"TERMINAL_CHANGED", "GATE_CHANGED", "CHECK_IN_COUNTER_CHANGED",
				"BAG_DROP_DESK_CHANGED", "BOARDING_TIME_CHANGED", "DEPARTURE_TIME_CHANGED",
				"DEPARTURE_DELAY_CHANGED", "STATUS_CHANGED", "ARRIVAL_TIME_CHANGED",
				"ARRIVAL_DELAY_CHANGED", "BAGGAGE_BELT_CHANGED"), types(changes));
	}

	@Test
	void doesNotTreatMissingNewLocationsAsConfirmedChanges() {
		List<FlightChange> changes = detect(snapshot(true));

		assertFalse(types(changes).stream().anyMatch(LOCATION_TYPES::contains));
	}

	private List<FlightChange> detect(MultiSourceFlightSnapshot snapshot) {
		ResolvedFlightContext previous = previous();
		return service.findLiveChanges(previous, snapshot, snapshot.flight().boardingTime(),
				snapshot.flight().estimatedDeparture(), previous.timeContext(), DETECTED_AT);
	}

	private Set<String> types(List<FlightChange> changes) {
		return changes.stream().map(FlightChange::type).collect(Collectors.toSet());
	}

	private MultiSourceFlightSnapshot snapshot(boolean missingLocations) {
		FlightResponse flight = new FlightResponse("BA117", DATE, "LHR", "London Heathrow",
				"CDG", "Paris Charles de Gaulle", DEPARTURE, DEPARTURE.plusMinutes(20), null,
				ARRIVAL, ARRIVAL.plusMinutes(15), null, DEPARTURE.minusMinutes(40),
				missingLocations ? null : "Terminal 2", missingLocations ? null : "Terminal 1",
				missingLocations ? null : "C18",
				missingLocations ? null : "B counters 3-4", "BOARDING", 20, 15,
				DETECTED_AT, DETECTED_AT, "TEST", null, null, true, false, false);
		return new MultiSourceFlightSnapshot(flight, missingLocations ? null : "B counter 6",
				missingLocations ? null : "Belt 6", Map.of(), Map.of(), List.of(), DETECTED_AT, DETECTED_AT);
	}

	private ResolvedFlightContext previous() {
		FlightTimeContext timeContext = new FlightTimeContext(DETECTED_AT, DEPARTURE, "+08:00",
				DEPARTURE.minusMinutes(50), DEPARTURE, null, null, DEPARTURE,
				10L, 60L, null, 60L, 0L, DETECTED_AT, DETECTED_AT, true, false);
		return new ResolvedFlightContext("BA117", "BA117", DATE, "MANUAL_ENTRY", false,
				"LHR", "London Heathrow", "CDG", "Paris Charles de Gaulle", null, null,
				DEPARTURE, null, null, DEPARTURE, ARRIVAL, ARRIVAL, null,
				null, null, "T1", "T8", "B32", null, "A counters 1-2", null,
				"A counter 5", "Belt 5", "EXPECTED", 5, 0, DETECTED_AT, DETECTED_AT,
				DETECTED_AT, DETECTED_AT, null, "TEST", null, null, true, false, null,
				false, false, Map.of(), Map.of(), Map.of(), List.of(), false, List.of(), timeContext);
	}
}
