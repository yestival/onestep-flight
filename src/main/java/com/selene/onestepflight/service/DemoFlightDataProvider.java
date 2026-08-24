package com.selene.onestepflight.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.selene.onestepflight.dto.FlightResponse;

@Component
public class DemoFlightDataProvider implements FlightDataProvider {

	private static final String DEMO_FLIGHT_NUMBER = "BA117";
	private static final LocalDate DEMO_DEPARTURE_DATE = LocalDate.of(2026, 9, 3);

	@Override
	public Optional<FlightResponse> findByFlightNumber(String flightNumber) {
		if (!DEMO_FLIGHT_NUMBER.equals(flightNumber)) {
			return Optional.empty();
		}

		Instant retrievedAt = Instant.now();
		return Optional.of(new FlightResponse(
				DEMO_FLIGHT_NUMBER,
				DEMO_DEPARTURE_DATE,
				"LHR",
				"London Heathrow",
				"JFK",
				"New York JFK",
				OffsetDateTime.of(DEMO_DEPARTURE_DATE.atTime(10, 30), ZoneId.of("Europe/London").getRules().getOffset(DEMO_DEPARTURE_DATE.atTime(10, 30))),
				null,
				null,
				OffsetDateTime.of(DEMO_DEPARTURE_DATE.atTime(13, 20), ZoneId.of("America/New_York").getRules().getOffset(DEMO_DEPARTURE_DATE.atTime(13, 20))),
				null,
				null,
				OffsetDateTime.of(DEMO_DEPARTURE_DATE.atTime(9, 45), ZoneId.of("Europe/London").getRules().getOffset(DEMO_DEPARTURE_DATE.atTime(9, 45))),
				"Terminal 5",
				"B32",
				"Zone D, counters 20-24",
				"BOARDING",
				0,
				0,
				retrievedAt,
				retrievedAt,
				"ONESTEP_DEMO_FIXTURE",
				null,
				null,
				false,
				true,
				true));
	}
}
