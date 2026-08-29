package com.selene.onestepflight.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.selene.onestepflight.dto.JourneyProblemRequest;
import com.selene.onestepflight.dto.JourneyProblemRequest.CurrentFlight;
import com.selene.onestepflight.dto.JourneyProblemRequest.FlightChangeInput;

final class JourneyProblemTestFixtures {
	private JourneyProblemTestFixtures() { }

	static JourneyProblemRequest request(String locale, boolean connection, String statement) {
		return new JourneyProblemRequest(connection, statement, locale, activeDelayedFlight());
	}

	static JourneyProblemRequest request(String locale, boolean connection, String statement, CurrentFlight flight) {
		return new JourneyProblemRequest(connection, statement, locale, flight);
	}

	static CurrentFlight activeDelayedFlight() {
		return flight(LocalDate.parse("2026-08-29"), "DEPARTURE",
				OffsetDateTime.parse("2026-08-29T16:30:00+01:00"),
				OffsetDateTime.parse("2026-08-29T17:30:00+01:00"), null,
				OffsetDateTime.parse("2026-08-29T17:30:00+01:00"),
				OffsetDateTime.parse("2026-08-29T19:30:00-04:00"),
				OffsetDateTime.parse("2026-08-29T20:30:00-04:00"), null,
				"DELAYED", 60, Instant.parse("2026-08-29T14:00:00Z"));
	}

	static CurrentFlight activeOnTimeFlight() {
		return flight(LocalDate.parse("2026-08-29"), "DEPARTURE",
				OffsetDateTime.parse("2026-08-29T16:30:00+01:00"), null, null,
				OffsetDateTime.parse("2026-08-29T16:30:00+01:00"),
				OffsetDateTime.parse("2026-08-29T19:30:00-04:00"), null, null,
				"ON_TIME", 0, Instant.parse("2026-08-29T14:00:00Z"));
	}

	static CurrentFlight cancelledFlight() {
		return flight(LocalDate.parse("2026-08-29"), "DEPARTURE",
				OffsetDateTime.parse("2026-08-29T16:30:00+01:00"), null, null,
				OffsetDateTime.parse("2026-08-29T16:30:00+01:00"),
				OffsetDateTime.parse("2026-08-29T19:30:00-04:00"), null, null,
				"CANCELLED", null, Instant.parse("2026-08-29T14:00:00Z"));
	}

	static CurrentFlight departedFlight() {
		return flight(LocalDate.parse("2026-08-29"), "DEPARTURE",
				OffsetDateTime.parse("2026-08-29T13:30:00+01:00"), null,
				OffsetDateTime.parse("2026-08-29T13:40:00+01:00"),
				OffsetDateTime.parse("2026-08-29T13:40:00+01:00"),
				OffsetDateTime.parse("2026-08-29T16:30:00-04:00"), null, null,
				"DEPARTED", null, Instant.parse("2026-08-29T14:00:00Z"));
	}

	static CurrentFlight historicalDelayedFlight() {
		return flight(LocalDate.parse("2026-07-29"), "DEPARTURE",
				OffsetDateTime.parse("2026-07-29T16:30:00+01:00"),
				OffsetDateTime.parse("2026-07-29T17:30:00+01:00"), null,
				OffsetDateTime.parse("2026-07-29T17:30:00+01:00"),
				OffsetDateTime.parse("2026-07-29T19:30:00-04:00"),
				OffsetDateTime.parse("2026-07-29T20:30:00-04:00"), null,
				"DELAYED", 60, Instant.parse("2026-08-29T14:00:00Z"));
	}

	static CurrentFlight flight(LocalDate date, String phase, OffsetDateTime scheduledDeparture,
			OffsetDateTime estimatedDeparture, OffsetDateTime actualDeparture, OffsetDateTime effectiveDeparture,
			OffsetDateTime scheduledArrival, OffsetDateTime estimatedArrival, OffsetDateTime actualArrival,
			String status, Integer delay, Instant currentTime) {
		return new CurrentFlight("BA117", date, "LHR", "London Heathrow", "JFK", "New York JFK",
				"ARRIVAL".equals(phase) ? "JFK" : "LHR",
				"ARRIVAL".equals(phase) ? "New York JFK" : "London Heathrow", phase,
				scheduledDeparture, estimatedDeparture, actualDeparture, effectiveDeparture,
				scheduledArrival, estimatedArrival, actualArrival,
				OffsetDateTime.parse(date + "T15:45:00+01:00"), "5", null, "B32", status,
				delay, null, null, currentTime, currentTime, currentTime,
				"29 Aug 2026, 15:00", List.of(new FlightChangeInput("STATUS_CHANGED", "SCHEDULED",
						status, true, currentTime)));
	}
}
