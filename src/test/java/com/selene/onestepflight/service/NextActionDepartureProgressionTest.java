package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.ActionCode;
import com.selene.onestepflight.dto.FlightTimeContext;
import com.selene.onestepflight.dto.JourneyContext;
import com.selene.onestepflight.dto.NextActionResponse;
import com.selene.onestepflight.dto.ResolvedFlightContext;

class NextActionDepartureProgressionTest {
	private static final Instant NOW = Instant.parse("2026-09-04T16:30:00Z");
	private final NextActionService service = new NextActionService(Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void offsetAwareDepartureOnTheRequestedLocalDateTriggersPassedTime() {
		ResolvedFlightContext flight = flight(LocalDate.of(2026, 9, 5),
				"2026-09-05T00:10+08:00", null, null, null, "EXPECTED");

		NextActionResponse result = service.calculate(flight, journey(flight, flight.departureDate(), false, false));

		assertThat(result.actionCode()).isEqualTo(ActionCode.ASK_FOR_HELP);
		assertThat(result.reason()).isEqualTo("DEPARTURE_TIME_PASSED");
		assertThat(result.titleZh()).isEqualTo("这趟航班已经离港");
		assertThat(result.descriptionZh()).contains("请工作人员帮忙");
		assertThat(result.destination()).contains("航空公司服务柜台").doesNotContain("Terminal");
	}

	@Test
	void staleSerializedEffectiveDepartureCannotOverrideCurrentFlightTimes() {
		ResolvedFlightContext flight = flight(LocalDate.of(2026, 9, 5),
				"2026-09-05T10:30+08:00", null, null,
				"2026-09-03T10:30+08:00", "EXPECTED");

		NextActionResponse result = service.calculate(flight, journey(flight, flight.departureDate(), false, false));

		assertThat(result.actionCode()).isEqualTo(ActionCode.CHECK_IN);
		assertThat(result.reason()).isEqualTo("CHECK_IN_NOT_COMPLETED");
	}

	@Test
	void selectedDateMismatchWithRefreshedFlightStopsNormalProgression() {
		ResolvedFlightContext flight = flight(LocalDate.of(2026, 9, 5),
				"2026-09-05T10:30+08:00", null, null, null, "EXPECTED");

		NextActionResponse result = service.calculate(flight,
				journey(flight, LocalDate.of(2026, 9, 4), false, false));

		assertThat(result.actionCode()).isEqualTo(ActionCode.ASK_FOR_HELP);
		assertThat(result.reason()).isEqualTo("FLIGHT_DATE_TIME_MISMATCH");
	}

	@Test
	void utcProviderTimeUsesTheDepartureAirportOffsetForTheSelectedDate() {
		ResolvedFlightContext flight = flight(LocalDate.of(2026, 9, 5),
				"2026-09-04T16:10Z", null, null, null, "EXPECTED", "+08:00");

		NextActionResponse result = service.calculate(flight, journey(flight, flight.departureDate(), false, false));

		assertThat(result.reason()).isEqualTo("DEPARTURE_TIME_PASSED");
	}

	@Test
	void exactlyFifteenMinutesPastDepartureTriggersPassedTime() {
		ResolvedFlightContext flight = flight(LocalDate.of(2026, 9, 5),
				"2026-09-04T16:15Z", null, null, null, "EXPECTED", "+08:00");

		NextActionResponse result = service.calculate(flight, journey(flight, flight.departureDate(), false, false));

		assertThat(result.reason()).isEqualTo("DEPARTURE_TIME_PASSED");
	}

	@Test
	void actualDepartureAtInitialQueryRequiresImmediateHelp() {
		ResolvedFlightContext flight = flight(LocalDate.of(2026, 9, 5),
				"2026-09-05T00:10+08:00", null,
				"2026-09-05T00:18+08:00", null, "EXPECTED");

		NextActionResponse result = service.calculate(flight, journey(flight, flight.departureDate(), false, false));

		assertThat(result.actionCode()).isEqualTo(ActionCode.ASK_FOR_HELP);
		assertThat(result.reason()).isEqualTo("FLIGHT_ALREADY_DEPARTED");
		assertThat(result.destination()).contains("Airline service desk").doesNotContain("B42");
	}

	@Test
	void liveDepartedOrArrivedStatusWithoutAnyTimeAlsoRequiresImmediateHelp() {
		for (String status : List.of("DEPARTED", "ARRIVED")) {
			ResolvedFlightContext flight = flight(LocalDate.of(2026, 9, 5),
					null, null, null, null, status);

			NextActionResponse result = service.calculate(flight,
					journey(flight, flight.departureDate(), false, false));

			assertThat(result.actionCode()).as(status).isEqualTo(ActionCode.ASK_FOR_HELP);
			assertThat(result.reason()).as(status).isEqualTo("FLIGHT_ALREADY_DEPARTED");
		}
	}

	@Test
	void timeOnlyDepartureDuringJourneyRequiresHelpUntilTravelerConfirmedBoarding() {
		ResolvedFlightContext flight = flight(LocalDate.of(2026, 9, 5),
				"2026-09-05T00:10+08:00", null, null, null, "EXPECTED");

		NextActionResponse result = service.calculate(flight, journey(flight, flight.departureDate(), true, false));

		assertThat(result.actionCode()).isEqualTo(ActionCode.ASK_FOR_HELP);
		assertThat(result.reason()).isEqualTo("DEPARTURE_TIME_PASSED");
	}

	@Test
	void confirmedBoardingAdvancesToInFlightEvenAfterLiveDeparture() {
		ResolvedFlightContext flight = flight(LocalDate.of(2026, 9, 5),
				null, null, null, null, "DEPARTED");

		NextActionResponse result = service.calculate(flight, journey(flight, flight.departureDate(), true, true));

		assertThat(result.actionCode()).isEqualTo(ActionCode.IN_FLIGHT);
		assertThat(result.reason()).isEqualTo("PASSENGER_BOARDED");
	}

	@Test
	void delayedDepartureAcrossMidnightStillBelongsToTheScheduledDepartureDate() {
		ResolvedFlightContext flight = flight(LocalDate.of(2026, 9, 4),
				"2026-09-04T23:50+08:00", "2026-09-05T00:10+08:00",
				null, null, "EXPECTED");

		NextActionResponse result = service.calculate(flight, journey(flight, flight.departureDate(), false, false));

		assertThat(result.reason()).isEqualTo("DEPARTURE_TIME_PASSED");
	}

	@Test
	void mismatchedFlightDateAndOffsetTimeIsNotMisreportedAsPassed() {
		ResolvedFlightContext flight = flight(LocalDate.of(2026, 9, 5),
				"2026-09-03T10:30+08:00", null, null, null, "EXPECTED");

		NextActionResponse result = service.calculate(flight, journey(flight, flight.departureDate(), false, false));

		assertThat(result.actionCode()).isEqualTo(ActionCode.ASK_FOR_HELP);
		assertThat(result.reason()).isEqualTo("FLIGHT_DATE_TIME_MISMATCH");
	}

	private JourneyContext journey(ResolvedFlightContext flight, LocalDate contextDate,
			boolean progressed, boolean boarded) {
		return new JourneyContext("BA117", contextDate, flight, false, null,
				"MANUAL_ENTRY", false, progressed, false, progressed,
				progressed, progressed, boarded, false, false, false, false,
				progressed ? flight.terminal() : null, progressed ? flight.gate() : null, true, false);
	}

	private ResolvedFlightContext flight(LocalDate departureDate, String scheduledText,
			String estimatedText, String actualText, String serializedEffectiveText, String status) {
		return flight(departureDate, scheduledText, estimatedText, actualText,
				serializedEffectiveText, status, null);
	}

	private ResolvedFlightContext flight(LocalDate departureDate, String scheduledText,
			String estimatedText, String actualText, String serializedEffectiveText, String status,
			String departureUtcOffset) {
		OffsetDateTime scheduled = time(scheduledText);
		OffsetDateTime estimated = time(estimatedText);
		OffsetDateTime actual = time(actualText);
		OffsetDateTime serializedEffective = time(serializedEffectiveText);
		Instant updated = NOW.minusSeconds(60);
		FlightTimeContext timeContext = departureUtcOffset == null ? null : new FlightTimeContext(
				NOW, NOW.atOffset(ZoneOffset.of(departureUtcOffset)), departureUtcOffset,
				null, scheduled, estimated, actual, serializedEffective,
				null, null, null, null, null, updated, updated, true, false);
		return new ResolvedFlightContext(
				"BA117", "BA117", departureDate, "MANUAL_ENTRY", false,
				"LHR", "London Heathrow", "JFK", "New York JFK",
				null, null, scheduled, estimated, actual, serializedEffective,
				scheduled == null ? null : scheduled.plusHours(8), null, null,
				null, null, "Terminal 5", "Terminal 8", "B42", null, "D", "D", null, null,
				status, 0, 0, updated, updated, updated, updated,
				null, "TEST", null, null, true, false, null,
				null, false, false, false, Map.of(), Map.of(), Map.of(), List.of(), false, List.of(), timeContext);
	}

	private OffsetDateTime time(String value) {
		return value == null ? null : OffsetDateTime.parse(value);
	}
}
