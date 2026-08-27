package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.ActionCode;
import com.selene.onestepflight.dto.JourneyContext;
import com.selene.onestepflight.dto.NextActionResponse;
import com.selene.onestepflight.dto.ResolveFlightRequest;
import com.selene.onestepflight.dto.ResolvedFlightContext;

class DemoScenarioFlightDataProviderTest {
	private static final Map<String, Set<String>> EXPECTED_CHANGES = expectedChanges();

	private final DemoScenarioFlightDataProvider provider = new DemoScenarioFlightDataProvider();
	private final ResolvedFlightService resolvedFlights = new ResolvedFlightService(
			new FlightService(null, null), null, provider, new FlightTimeService());

	@Test
	void everyScenarioUsesTheProductionComparisonPathAndProducesOnlyItsExpectedChanges() {
		EXPECTED_CHANGES.forEach((scenario, expected) -> {
			ResolvedFlightContext before = initial(scenario);
			assertThat(before.latestChanges()).as(scenario + " BEFORE").isEmpty();
			ResolvedFlightContext after = resolvedFlights.refresh(before, true);

			assertThat(types(after)).as(scenario).isEqualTo(expected);
		});
	}

	@Test
	void everySecondRefreshIsStableAndDoesNotProduceAnotherChangeBatch() {
		EXPECTED_CHANGES.keySet().forEach(scenario -> {
			ResolvedFlightContext before = initial(scenario);
			ResolvedFlightContext after = resolvedFlights.refresh(before, true);
			ResolvedFlightContext repeatedAfter = resolvedFlights.refresh(after, true);

			assertThat(repeatedAfter.latestChanges()).as(scenario).isEmpty();
		});
	}

	@Test
	void snapshotsKeepOneAnchorAndCarryBagDropAndBaggageBeltThroughTheResolvedService() {
		EXPECTED_CHANGES.keySet().forEach(scenario -> {
			ResolvedFlightContext before = initial(scenario);
			ResolvedFlightContext after = resolvedFlights.refresh(before, true);

			assertThat(after.scheduledDeparture()).as(scenario).isEqualTo(before.scheduledDeparture());
			assertThat(after.scheduledArrival()).as(scenario).isEqualTo(before.scheduledArrival());
			if (!"BOARDING_TIME_CHANGE".equals(scenario)) {
				assertThat(after.timeContext().boardingTime()).as(scenario)
						.isEqualTo(before.timeContext().boardingTime());
			}
			if (!Set.of("DEPARTURE_TIME_CHANGE", "DELAY").contains(scenario)) {
				assertThat(after.effectiveDeparture().toInstant()).as(scenario)
						.isEqualTo(before.effectiveDeparture().toInstant());
			}
			if (!"ARRIVAL_TIME_CHANGE".equals(scenario)) {
				assertThat(preferredArrival(after).toInstant()).as(scenario)
						.isEqualTo(preferredArrival(before).toInstant());
			}
			assertThat(before.dedicatedBagDropDesk()).as(scenario).isEqualTo("Counter 5");
			assertThat(before.arrivalBaggageBelt()).as(scenario).isEqualTo("Belt 5");
		});
	}

	@Test
	void boardingScenarioChangesTheRealNextActionFromWaitToBoardNow() {
		ResolvedFlightContext before = initial("BOARDING");
		NextActionService nextActions = new NextActionService();
		NextActionResponse beforeAction = nextActions.calculate(before, boardingContext(before));
		ResolvedFlightContext after = resolvedFlights.refresh(before, true);
		NextActionResponse afterAction = nextActions.calculate(after, boardingContext(after));

		assertThat(beforeAction.actionCode()).isEqualTo(ActionCode.WAIT);
		assertThat(afterAction.actionCode()).isEqualTo(ActionCode.BOARD_NOW);
		assertThat(afterAction.titleZh()).isEqualTo("现在准备登机");
	}

	private ResolvedFlightContext initial(String scenario) {
		return resolvedFlights.resolve(new ResolveFlightRequest("BA117",
				LocalDate.now(ZoneOffset.UTC), true, scenario, null, false));
	}

	private Set<String> types(ResolvedFlightContext flight) {
		return flight.latestChanges().stream().map(change -> change.type()).collect(Collectors.toSet());
	}

	private java.time.OffsetDateTime preferredArrival(ResolvedFlightContext flight) {
		if (flight.actualArrival() != null) return flight.actualArrival();
		if (flight.estimatedArrival() != null) return flight.estimatedArrival();
		return flight.scheduledArrival();
	}

	private JourneyContext boardingContext(ResolvedFlightContext flight) {
		return new JourneyContext("BA117", flight.departureDate(), flight, true, "BOARDING",
				"MANUAL_ENTRY", false, true, false, true, true, true,
				false, false, false, false, false, flight.terminal(), flight.gate(), true, false);
	}

	private static Map<String, Set<String>> expectedChanges() {
		Map<String, Set<String>> changes = new LinkedHashMap<>();
		changes.put("NORMAL", Set.of());
		changes.put("TERMINAL_CHANGE", Set.of("TERMINAL_CHANGED"));
		changes.put("GATE_CHANGE", Set.of("GATE_CHANGED"));
		changes.put("CHECK_IN_COUNTER_CHANGE", Set.of("CHECK_IN_COUNTER_CHANGED"));
		changes.put("BAG_DROP_DESK_CHANGE", Set.of("BAG_DROP_DESK_CHANGED"));
		changes.put("BOARDING_TIME_CHANGE", Set.of("BOARDING_TIME_CHANGED"));
		changes.put("DEPARTURE_TIME_CHANGE", Set.of("DEPARTURE_TIME_CHANGED"));
		changes.put("DELAY", Set.of("STATUS_CHANGED", "DEPARTURE_TIME_CHANGED", "DEPARTURE_DELAY_CHANGED"));
		changes.put("BOARDING", Set.of("STATUS_CHANGED"));
		changes.put("CANCEL", Set.of("STATUS_CHANGED"));
		changes.put("DEPARTED", Set.of("STATUS_CHANGED"));
		changes.put("DIVERTED", Set.of("STATUS_CHANGED"));
		changes.put("RETURNED", Set.of("STATUS_CHANGED"));
		changes.put("ARRIVAL_TIME_CHANGE", Set.of("ARRIVAL_TIME_CHANGED", "ARRIVAL_DELAY_CHANGED"));
		changes.put("ARRIVED", Set.of("STATUS_CHANGED"));
		changes.put("BAGGAGE_BELT_CHANGE", Set.of("BAGGAGE_BELT_CHANGED"));
		changes.put("STALE", Set.of("DATA_FRESHNESS_CHANGED"));
		return Map.copyOf(changes);
	}
}
