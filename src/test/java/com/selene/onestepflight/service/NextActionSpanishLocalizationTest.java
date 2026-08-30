package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.FlightTimeContext;
import com.selene.onestepflight.dto.JourneyContext;
import com.selene.onestepflight.dto.NextActionResponse;
import com.selene.onestepflight.dto.ResolvedFlightContext;

class NextActionSpanishLocalizationTest {
	private static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");
	private static final LocalDate DEPARTURE_DATE = LocalDate.of(2026, 8, 24);
	private final NextActionService service = new NextActionService(Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void everyReachableReasonHasExplicitSpanishCopy() {
		List<NextActionResponse> responses = allReachableReasons();

		assertThat(responses).extracting(NextActionResponse::reason).containsExactlyInAnyOrder(
				"PASSENGER_REQUESTED_ASSISTANCE",
				"FLIGHT_CANCELLED",
				"FLIGHT_DATE_TIME_MISMATCH",
				"FLIGHT_ALREADY_DEPARTED",
				"DEPARTURE_TIME_PASSED",
				"CHECK_IN_NOT_COMPLETED",
				"BAGGAGE_STATUS_REQUIRED",
				"CHECKED_BAGGAGE_NOT_DROPPED",
				"SECURITY_NOT_COMPLETED",
				"STALE_DATA_NEAR_DEPARTURE",
				"SIGNIFICANT_DELAY_AFTER_REQUIRED_STEPS",
				"GATE_MISSING_NEAR_DEPARTURE",
				"GATE_NOT_PUBLISHED",
				"TERMINAL_CHANGED",
				"GATE_CHANGED",
				"SECURITY_COMPLETED_GATE_AVAILABLE",
				"BOARDING_WINDOW_OPEN",
				"AT_GATE_BEFORE_BOARDING",
				"PASSENGER_BOARDED",
				"PASSENGER_LANDED_CHECKED_BAGGAGE_PENDING",
				"ARRIVAL_STEPS_COMPLETED_EXIT_PENDING",
				"PASSENGER_EXITED_AIRPORT");
		assertThat(responses).allSatisfy(response -> {
			assertThat(response.titleEs()).as(response.reason() + " titleEs").isNotBlank();
			assertThat(response.descriptionEs()).as(response.reason() + " descriptionEs").isNotBlank();
			assertThat(response.titleEs()).as(response.reason() + " passenger voice").doesNotContainIgnoringCase("usted");
			assertThat(response.descriptionEs()).as(response.reason() + " passenger voice").doesNotContainIgnoringCase("usted");
		});
	}

	@Test
	void criticalOperationalReasonsKeepTheirSpecificSpanishMeaning() {
		Map<String, NextActionResponse> responses = allReachableReasons().stream()
				.collect(Collectors.toMap(NextActionResponse::reason, Function.identity()));

		assertThat(responses.get("FLIGHT_CANCELLED").titleEs()).contains("cancelado");
		assertThat(responses.get("FLIGHT_CANCELLED").descriptionEs()).contains("No sigas", "reorganice tu viaje");
		assertThat(responses.get("FLIGHT_DATE_TIME_MISMATCH").titleEs()).contains("fecha", "hora de salida");
		assertThat(responses.get("FLIGHT_DATE_TIME_MISMATCH").descriptionEs()).contains("no coincide", "confirme la fecha");
		assertThat(responses.get("FLIGHT_ALREADY_DEPARTED").titleEs()).contains("ya ha salido");
		assertThat(responses.get("DEPARTURE_TIME_PASSED").titleEs()).contains("ya ha salido");
		assertThat(responses.get("STALE_DATA_NEAR_DEPARTURE").titleEs()).contains("desactualizada");
		assertThat(responses.get("STALE_DATA_NEAR_DEPARTURE").descriptionEs()).contains("más de 10 minutos");
		assertThat(responses.get("SIGNIFICANT_DELAY_AFTER_REQUIRED_STEPS").titleEs()).contains("retraso importante");
		assertThat(responses.get("GATE_MISSING_NEAR_DEPARTURE").descriptionEs()).contains("salida está próxima", "confirme tu puerta");
		assertThat(responses.get("GATE_NOT_PUBLISHED").descriptionEs()).contains("aún no está anunciada", "Quédate");
		assertThat(responses.get("TERMINAL_CHANGED").titleEs()).contains("de 4 a 5");
		assertThat(responses.get("TERMINAL_CHANGED").descriptionEs()).contains("terminal anterior", "ruta entre terminales");
		assertThat(responses.get("GATE_CHANGED").titleEs()).contains("de A1 a B42");
		assertThat(responses.get("GATE_CHANGED").descriptionEs()).contains("puerta anterior", "nueva puerta");
		assertThat(responses.get("CHECKED_BAGGAGE_NOT_DROPPED").descriptionEs())
				.contains("No se ha publicado un mostrador específico", "te indique dónde entregarlo");
	}

	private List<NextActionResponse> allReachableReasons() {
		ResolvedFlightContext standard = new FlightState().build();
		ResolvedFlightContext cancelled = new FlightState().status("CANCELLED").build();
		ResolvedFlightContext departed = new FlightState().status("DEPARTED").build();
		ResolvedFlightContext passed = new FlightState().departure(NOW.minus(Duration.ofMinutes(15))).build();
		ResolvedFlightContext missingBagDrop = new FlightState().dedicatedBagDropDesk(null).build();
		ResolvedFlightContext stale = new FlightState().departure(NOW.plus(Duration.ofMinutes(60))).stale().build();
		ResolvedFlightContext delayed = new FlightState().delayMinutes(45).build();
		ResolvedFlightContext missingGateSoon = new FlightState().departure(NOW.plus(Duration.ofMinutes(60))).gate(null).build();
		ResolvedFlightContext missingGateEarly = new FlightState().gate(null).build();
		ResolvedFlightContext boarding = new FlightState().status("BOARDING").build();

		return List.of(
				calculate(standard, new JourneyState().needsAssistance()),
				calculate(cancelled, new JourneyState()),
				calculate(standard, new JourneyState().departureDate(DEPARTURE_DATE.minusDays(1))),
				calculate(departed, new JourneyState()),
				calculate(passed, new JourneyState()),
				calculate(standard, new JourneyState()),
				calculate(standard, new JourneyState().checkedIn()),
				calculate(missingBagDrop, new JourneyState().checkedIn().checkedBaggage(true)),
				calculate(standard, new JourneyState().checkedIn().checkedBaggage(false)),
				calculate(stale, afterSecurity()),
				calculate(delayed, afterSecurity()),
				calculate(missingGateSoon, afterSecurity()),
				calculate(missingGateEarly, afterSecurity()),
				calculate(standard, afterSecurity().previousTerminal("4").previousGate("B42")),
				calculate(standard, afterSecurity().previousTerminal("5").previousGate("A1")),
				calculate(standard, afterSecurity().previousTerminal("5").previousGate("B42")),
				calculate(boarding, afterSecurity().atGate().previousTerminal("5").previousGate("B42")),
				calculate(standard, afterSecurity().atGate().previousTerminal("5").previousGate("B42")),
				calculate(standard, new JourneyState().checkedIn().checkedBaggage(false).boarded()),
				calculate(standard, new JourneyState().checkedIn().checkedBaggage(true).boarded().landed()),
				calculate(standard, new JourneyState().checkedIn().checkedBaggage(false).boarded().landed()),
				calculate(standard, new JourneyState().checkedIn().checkedBaggage(false).boarded().landed().exitedAirport()));
	}

	private JourneyState afterSecurity() {
		return new JourneyState().checkedIn().checkedBaggage(false).securityCleared();
	}

	private NextActionResponse calculate(ResolvedFlightContext flight, JourneyState state) {
		return service.calculate(flight, state.toContext(flight));
	}

	private static final class JourneyState {
		private LocalDate departureDate = DEPARTURE_DATE;
		private boolean checkedIn;
		private Boolean checkedBaggage;
		private boolean bagDropped;
		private boolean securityCleared;
		private boolean atGate;
		private boolean boarded;
		private boolean landed;
		private boolean baggageCollected;
		private boolean exitedAirport;
		private boolean needsAssistance;
		private String previousTerminal;
		private String previousGate;

		private JourneyState departureDate(LocalDate value) { departureDate = value; return this; }
		private JourneyState checkedIn() { checkedIn = true; return this; }
		private JourneyState checkedBaggage(Boolean value) { checkedBaggage = value; return this; }
		private JourneyState securityCleared() { securityCleared = true; return this; }
		private JourneyState atGate() { atGate = true; return this; }
		private JourneyState boarded() { boarded = true; return this; }
		private JourneyState landed() { landed = true; return this; }
		private JourneyState exitedAirport() { exitedAirport = true; return this; }
		private JourneyState needsAssistance() { needsAssistance = true; return this; }
		private JourneyState previousTerminal(String value) { previousTerminal = value; return this; }
		private JourneyState previousGate(String value) { previousGate = value; return this; }

		private JourneyContext toContext(ResolvedFlightContext flight) {
			return new JourneyContext("BA117", departureDate, flight, false, null,
					"MANUAL_ENTRY", false, checkedIn, checkedBaggage, bagDropped,
					securityCleared, atGate, boarded, landed, baggageCollected, exitedAirport,
					needsAssistance, previousTerminal, previousGate, true, false);
		}
	}

	private static final class FlightState {
		private OffsetDateTime departure = NOW.plus(Duration.ofHours(5)).atOffset(ZoneOffset.UTC);
		private String terminal = "5";
		private String gate = "B42";
		private String dedicatedBagDropDesk = "D 20-24";
		private String status = "EXPECTED";
		private int delayMinutes;
		private boolean stale;

		private FlightState departure(Instant value) { departure = value.atOffset(ZoneOffset.UTC); return this; }
		private FlightState gate(String value) { gate = value; return this; }
		private FlightState dedicatedBagDropDesk(String value) { dedicatedBagDropDesk = value; return this; }
		private FlightState status(String value) { status = value; return this; }
		private FlightState delayMinutes(int value) { delayMinutes = value; return this; }
		private FlightState stale() { stale = true; return this; }

		private ResolvedFlightContext build() {
			Instant updatedAt = stale ? NOW.minus(Duration.ofMinutes(11)) : NOW.minus(Duration.ofMinutes(1));
			FlightTimeContext timeContext = new FlightTimeContext(
					NOW, NOW.atOffset(ZoneOffset.UTC), "+00:00", null,
					departure, null, null, departure,
					null, null, null, Duration.between(NOW, departure.toInstant()).toMinutes(),
					Duration.between(updatedAt, NOW).toMinutes(), updatedAt, NOW,
					true, stale);
			return new ResolvedFlightContext(
					"BA117", "BA117", DEPARTURE_DATE, "MANUAL_ENTRY", false,
					"LHR", "London Heathrow", "JFK", "New York JFK",
					null, null, departure, null, null, departure,
					departure.plusHours(8), null, null,
					null, null, terminal, "8", gate, null, "D", "D", dedicatedBagDropDesk, "7",
					status, delayMinutes, 0, updatedAt, NOW, NOW, updatedAt,
					null, "TEST", null, null, true, false, null,
					null, false, false, stale, Map.of(), Map.of(), Map.of(), List.of(), false, List.of(), timeContext);
		}
	}
}
