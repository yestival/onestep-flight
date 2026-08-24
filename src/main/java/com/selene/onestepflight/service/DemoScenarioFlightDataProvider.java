package com.selene.onestepflight.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.selene.onestepflight.dto.FlightResponse;

@Component
public class DemoScenarioFlightDataProvider {

	private static final Set<String> SCENARIOS = Set.of("NORMAL", "GATE_CHANGE", "DELAY", "CANCEL", "STALE");
	private final Map<String, FlightResponse> scenarioState = new ConcurrentHashMap<>();

	public FlightResponse find(String flightNumber, String requestedScenario, boolean refresh) {
		String scenario = normalize(requestedScenario);
		if (!SCENARIOS.contains(scenario)) throw new FlightNotFoundException(flightNumber);
		String key = flightNumber + ":" + scenario;
		if (!refresh) return scenarioState.computeIfAbsent(key, ignored -> build(flightNumber, scenario, false));
		FlightResponse refreshed = build(flightNumber, scenario, true);
		scenarioState.put(key, refreshed);
		return refreshed;
	}

	public FlightResponse findInitial(String flightNumber, String requestedScenario) {
		String scenario = normalize(requestedScenario);
		if (!SCENARIOS.contains(scenario)) throw new FlightNotFoundException(flightNumber);
		FlightResponse initial = build(flightNumber, scenario, false);
		scenarioState.put(flightNumber + ":" + scenario, initial);
		return initial;
	}

	private FlightResponse build(String flightNumber, String scenario, boolean refresh) {
		Instant serverNow = Instant.now();
		OffsetDateTime scheduled = serverNow.atOffset(ZoneOffset.UTC).plusMinutes(100).withSecond(0).withNano(0);
		OffsetDateTime estimated = null;
		String terminal = "Terminal 5";
		String gate = "B32";
		String status = "ON_TIME";
		int delay = 0;
		Instant sourceUpdatedAt = serverNow;
		Instant retrievedAt = serverNow;

		if ("NORMAL".equals(scenario) && refresh) status = "BOARDING";
		if ("GATE_CHANGE".equals(scenario) && refresh) gate = "C18";
		if ("DELAY".equals(scenario)) {
			status = "DELAYED";
			delay = 60;
			estimated = scheduled.plusMinutes(delay);
		}
		if ("CANCEL".equals(scenario)) status = "CANCELLED";
		if ("STALE".equals(scenario)) {
			scheduled = serverNow.atOffset(ZoneOffset.UTC).plusMinutes(75).withSecond(0).withNano(0);
			sourceUpdatedAt = serverNow.minusSeconds(20 * 60L);
			retrievedAt = sourceUpdatedAt;
		}

		return new FlightResponse(
				flightNumber,
				scheduled.toLocalDate(),
				"LHR",
				"London Heathrow",
				"JFK",
				"New York JFK",
				scheduled,
				estimated,
				null,
				scheduled.plusHours(8),
				estimated == null ? null : estimated.plusHours(8),
				null,
				scheduled.minusMinutes(45),
				terminal,
				gate,
				null,
				status,
				delay,
				delay,
				sourceUpdatedAt,
				retrievedAt,
				"ONESTEP_SIMULATED_SCENARIO_" + scenario,
				null,
				null,
				false,
				true,
				true);
	}

	private String normalize(String value) {
		return value == null ? "NORMAL" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
	}
}
