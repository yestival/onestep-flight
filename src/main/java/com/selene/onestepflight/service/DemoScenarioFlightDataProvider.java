package com.selene.onestepflight.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.selene.onestepflight.dto.FlightResponse;

@Component
public class DemoScenarioFlightDataProvider {
	private static final String SOURCE_PREFIX = "ONESTEP_SIMULATED_SCENARIO_";

	static final Set<String> SCENARIOS = Set.of(
			"NORMAL", "TERMINAL_CHANGE", "GATE_CHANGE", "CHECK_IN_COUNTER_CHANGE",
			"BAG_DROP_DESK_CHANGE", "BOARDING_TIME_CHANGE", "DEPARTURE_TIME_CHANGE",
			"DELAY", "BOARDING", "CANCEL", "DEPARTED", "DIVERTED", "RETURNED",
			"ARRIVAL_TIME_CHANGE", "ARRIVED", "BAGGAGE_BELT_CHANGE", "STALE");

	private final Map<String, ScenarioState> scenarioStates = new ConcurrentHashMap<>();

	public MultiSourceFlightSnapshot findInitialSnapshot(String flightNumber, String requestedScenario) {
		return findInitialSnapshot(null, flightNumber, requestedScenario);
	}

	public MultiSourceFlightSnapshot findInitialSnapshot(String demoSessionId, String flightNumber,
			String requestedScenario) {
		String scenario = requireScenario(flightNumber, requestedScenario);
		ScenarioState state = new ScenarioState(Instant.now().truncatedTo(ChronoUnit.SECONDS), 0);
		scenarioStates.put(key(demoSessionId, flightNumber, scenario), state);
		return build(flightNumber, scenario, state);
	}

	public MultiSourceFlightSnapshot findSnapshot(String flightNumber, String requestedScenario, boolean refresh) {
		return findSnapshot(null, flightNumber, requestedScenario, refresh);
	}

	public MultiSourceFlightSnapshot findSnapshot(String demoSessionId, String flightNumber,
			String requestedScenario, boolean refresh) {
		String scenario = requireScenario(flightNumber, requestedScenario);
		String key = key(demoSessionId, flightNumber, scenario);
		ScenarioState state = scenarioStates.compute(key, (ignored, current) -> {
			ScenarioState existing = current == null
					? new ScenarioState(Instant.now().truncatedTo(ChronoUnit.SECONDS), 0) : current;
			return refresh && existing.stage() == 0
					? new ScenarioState(existing.anchor(), 1) : existing;
		});
		return build(flightNumber, scenario, state);
	}

	public void clearSession(String demoSessionId) {
		String prefix = sessionKey(demoSessionId) + ":";
		scenarioStates.keySet().removeIf(key -> key.startsWith(prefix));
	}

	boolean hasSessionState(String demoSessionId) {
		String normalized = DemoRecordingSessionId.normalize(demoSessionId);
		if (normalized == null) return false;
		String prefix = normalized + ":";
		return scenarioStates.keySet().stream().anyMatch(key -> key.startsWith(prefix));
	}

	int scenarioStateCount() {
		return scenarioStates.size();
	}

	static boolean isControlledScenario(FlightResponse flight) {
		return flight != null && flight.demo() && !flight.live()
				&& flight.dataSource() != null && flight.dataSource().startsWith(SOURCE_PREFIX);
	}

	private MultiSourceFlightSnapshot build(String flightNumber, String scenario, ScenarioState state) {
		boolean after = state.stage() == 1;
		Instant anchor = state.anchor();
		OffsetDateTime scheduledDeparture = anchor.atOffset(ZoneOffset.UTC).plusMinutes(120)
				.withSecond(0).withNano(0);
		OffsetDateTime scheduledArrival = scheduledDeparture.plusHours(8);
		OffsetDateTime boardingTime = scheduledDeparture.minusMinutes(45);
		OffsetDateTime estimatedDeparture = null;
		OffsetDateTime actualDeparture = null;
		OffsetDateTime estimatedArrival = null;
		OffsetDateTime actualArrival = null;
		String terminal = "5";
		String arrivalTerminal = "8";
		String gate = "B32";
		String checkInCounter = "Zone A, counters 1-4";
		String bagDropDesk = "Counter 5";
		String baggageBelt = "Belt 5";
		String status = "ON_TIME";
		int departureDelay = 0;
		int arrivalDelay = 0;
		Instant sourceUpdatedAt = anchor;
		Instant retrievedAt = anchor;

		if (after) {
			switch (scenario) {
				case "TERMINAL_CHANGE" -> terminal = "3";
				case "GATE_CHANGE" -> gate = "C18";
				case "CHECK_IN_COUNTER_CHANGE" -> checkInCounter = "Zone B, counters 21-24";
				case "BAG_DROP_DESK_CHANGE" -> bagDropDesk = "Counter 26";
				case "BOARDING_TIME_CHANGE" -> boardingTime = boardingTime.plusMinutes(20);
				case "DEPARTURE_TIME_CHANGE" -> estimatedDeparture = scheduledDeparture.plusMinutes(20);
				case "DELAY" -> {
					status = "DELAYED";
					estimatedDeparture = scheduledDeparture.plusMinutes(60);
					departureDelay = 60;
				}
				case "BOARDING" -> status = "BOARDING";
				case "CANCEL" -> status = "CANCELLED";
				case "DEPARTED" -> {
					status = "DEPARTED";
					actualDeparture = scheduledDeparture;
				}
				case "DIVERTED" -> status = "DIVERTED";
				case "RETURNED" -> status = "RETURNED";
				case "ARRIVAL_TIME_CHANGE" -> {
					estimatedArrival = scheduledArrival.plusMinutes(40);
					arrivalDelay = 40;
				}
				case "ARRIVED" -> {
					status = "ARRIVED";
					actualArrival = scheduledArrival;
				}
				case "BAGGAGE_BELT_CHANGE" -> baggageBelt = "Belt 8";
				case "STALE" -> {
					sourceUpdatedAt = anchor.minusSeconds(20 * 60L);
					retrievedAt = sourceUpdatedAt;
				}
				default -> {
					// NORMAL deliberately returns the unchanged baseline.
				}
			}
		}

		FlightResponse flight = new FlightResponse(
				flightNumber,
				scheduledDeparture.toLocalDate(),
				"LHR", "London Heathrow",
				"JFK", "New York JFK",
				scheduledDeparture, estimatedDeparture, actualDeparture,
				scheduledArrival, estimatedArrival, actualArrival,
				boardingTime, terminal, arrivalTerminal, gate, checkInCounter, status,
				departureDelay, arrivalDelay, sourceUpdatedAt, retrievedAt,
				SOURCE_PREFIX + scenario, null, null,
				false, false, true);
		return new MultiSourceFlightSnapshot(flight, bagDropDesk, baggageBelt,
				Map.of(), Map.of(), List.of(), retrievedAt, sourceUpdatedAt);
	}

	private String requireScenario(String flightNumber, String requestedScenario) {
		String scenario = normalize(requestedScenario);
		if (!SCENARIOS.contains(scenario)) throw new FlightNotFoundException(flightNumber);
		return scenario;
	}

	private String key(String demoSessionId, String flightNumber, String scenario) {
		return sessionKey(demoSessionId) + ":" + flightNumber + ":" + scenario;
	}

	private String sessionKey(String demoSessionId) {
		if (demoSessionId == null || demoSessionId.isBlank()) return "LEGACY";
		String normalized = DemoRecordingSessionId.normalize(demoSessionId);
		if (normalized == null) throw new InvalidDemoSessionIdException();
		return normalized;
	}

	private String normalize(String value) {
		return value == null ? "NORMAL" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
	}

	private record ScenarioState(Instant anchor, int stage) {
	}
}
