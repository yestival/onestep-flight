package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.ProviderFlightResult;

class MultiSourceFlightSemanticMergeTest {
	private static final LocalDate DATE = LocalDate.of(2026, 9, 4);
	private static final Instant UPDATED = Instant.parse("2026-09-04T15:55:00Z");

	@Test
	void equivalentProviderFormatsDoNotBecomeConflictsAndMoreSpecificCheckInWins() {
		ProviderFlightResult aero = result("AERODATABOX", "BA117", "CDG", "Paris Charles de Gaulle",
				"XMN", "Xiamen Gaoqi", "2026-09-04T16:10Z", "T2", "B42", "D", "EXPECTED");
		ProviderFlightResult serp = result("SERPAPI", "BA 117", "CDG",
				"Aéroport de Paris-Charles de Gaulle", "XMN", "Xiamen Gaoqi International Airport",
				"2026-09-05T00:10+08:00", "Terminal 2", "Gate B 42",
				"Area D, counters 20–24", "ON_TIME");

		MultiSourceFlightSnapshot snapshot = service(aero, serp)
				.findFlight("BA117", DATE, null, null, false);

		assertThat(snapshot.fieldProvenance().get("flightNumber").conflict()).isFalse();
		assertThat(snapshot.fieldProvenance().get("departureAirportName").conflict()).isFalse();
		assertThat(snapshot.fieldProvenance().get("arrivalAirportName").conflict()).isFalse();
		assertThat(snapshot.fieldProvenance().get("scheduledDeparture").conflict()).isFalse();
		assertThat(snapshot.fieldProvenance().get("terminal").conflict()).isFalse();
		assertThat(snapshot.fieldProvenance().get("gate").conflict()).isFalse();
		assertThat(snapshot.fieldProvenance().get("status").conflict()).isFalse();
		assertThat(snapshot.fieldProvenance().get("checkInDeskOrZone").conflict()).isFalse();
		assertThat(snapshot.flight().checkInCounter()).isEqualTo("Area D, counters 20–24");
		assertThat(snapshot.providerConflicts()).isEmpty();
		assertThat(snapshot.fieldProvenance().get("terminal").candidateValues())
				.extracting(candidate -> candidate.value())
				.containsExactlyInAnyOrder("T2", "Terminal 2");
	}

	@Test
	void realOperationalContradictionsRemainBlockingConflicts() {
		ProviderFlightResult aero = result("AERODATABOX", "BA117", "CDG", "Paris CDG",
				"XMN", "Xiamen", "2026-09-04T16:10Z", "T2", "B42",
				"D counters 20-24", "EXPECTED");
		ProviderFlightResult serp = result("SERPAPI", "BA117", "LHR", "London Heathrow",
				"XMN", "Xiamen", "2026-09-04T16:30Z", "Terminal 3", "Gate B43",
				"Area D counters 30-34", "CANCELLED");

		MultiSourceFlightSnapshot snapshot = service(aero, serp)
				.findFlight("BA117", DATE, null, null, false);

		assertThat(snapshot.providerConflicts())
				.allMatch(conflict -> conflict.blocking())
				.extracting(conflict -> conflict.field())
				.contains("departureAirport", "terminal", "gate", "checkInDeskOrZone", "status",
						"estimatedDeparture", "boardingTime");
	}

	@Test
	void anExactDateProviderOutranksAnAdjacentDateProviderAcrossTheAggregate() {
		ProviderFlightResult adjacent = withDate(result("AERODATABOX", "BA117", "LHR", "London",
				"JFK", "New York", "2026-09-05T10:00Z", null, null, null, "EXPECTED"),
				DATE.plusDays(1));
		ProviderFlightResult exact = result("SERPAPI", "BA117", "CDG", "Paris",
				"XMN", "Xiamen", "2026-09-04T16:10Z", null, null, null, "EXPECTED");

		MultiSourceFlightSnapshot snapshot = service(adjacent, exact)
				.findFlight("BA117", DATE, null, null, false);

		assertThat(snapshot.flight().departureDate()).isEqualTo(DATE);
		assertThat(snapshot.flight().departureAirport()).isEqualTo("CDG");
		assertThat(snapshot.fieldProvenance().get("departureDate").candidateValues()).hasSize(1);
	}

	private MultiSourceFlightService service(ProviderFlightResult... results) {
		List<OperationalFlightDataProvider> providers = java.util.Arrays.stream(results)
				.map(result -> new FixedProvider(result.provider(), result))
				.map(OperationalFlightDataProvider.class::cast)
				.toList();
		return new MultiSourceFlightService(providers, Duration.ofMinutes(5),
				Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(1), Runnable::run);
	}

	private ProviderFlightResult result(String provider, String flightNumber,
			String departureAirport, String departureAirportName,
			String arrivalAirport, String arrivalAirportName,
			String departureText, String terminal, String gate, String checkIn, String status) {
		OffsetDateTime departure = OffsetDateTime.parse(departureText);
		return new ProviderFlightResult(provider, "SUCCESS", null, flightNumber, DATE,
				departureAirport, departureAirportName, arrivalAirport, arrivalAirportName,
				departure, departure, null, departure.plusHours(11), null, null, departure.minusMinutes(45),
				terminal, gate, checkIn, null, null, status, null, null, "LIVE",
				UPDATED, UPDATED, null, null, true, false);
	}

	private ProviderFlightResult withDate(ProviderFlightResult result, LocalDate date) {
		return new ProviderFlightResult(result.provider(), result.outcome(), result.errorCode(), result.flightNumber(),
				date, result.departureAirport(), result.departureAirportName(), result.arrivalAirport(),
				result.arrivalAirportName(), result.scheduledDeparture(), result.estimatedDeparture(),
				result.actualDeparture(), result.scheduledArrival(), result.estimatedArrival(), result.actualArrival(),
				result.boardingTime(), result.terminal(), result.gate(), result.checkInDeskOrZone(),
				result.dedicatedBagDropDesk(), result.arrivalBaggageBelt(), result.status(),
				result.departureDelayMinutes(), result.arrivalDelayMinutes(), result.quality(),
				result.sourceUpdatedAt(), result.retrievedAt(), result.sourceUrl(), result.codeshare(),
				result.live(), result.fromCache());
	}

	private record FixedProvider(String providerName, ProviderFlightResult result)
			implements OperationalFlightDataProvider {
		@Override
		public ProviderFlightResult findFlight(String flightNumber, LocalDate departureDate,
				String departureAirportHint, String arrivalAirportHint, boolean refresh) {
			return result;
		}
	}
}
