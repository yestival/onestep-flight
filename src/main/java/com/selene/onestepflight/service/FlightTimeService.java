package com.selene.onestepflight.service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.stereotype.Service;

import com.selene.onestepflight.dto.FlightResponse;
import com.selene.onestepflight.dto.FlightTimeContext;

@Service
public class FlightTimeService {

	private static final long STALE_AFTER_MINUTES = 10;

	public FlightTimeContext build(FlightResponse flight, OffsetDateTime boardingTime) {
		Instant serverNow = Instant.now();
		OffsetDateTime effectiveDeparture = firstNonNull(
				flight.actualDeparture(), flight.estimatedDeparture(), flight.scheduledDeparture());
		ZoneOffset departureOffset = departureOffset(flight, boardingTime);
		Instant ageReference = flight.lastUpdated() != null ? flight.lastUpdated() : flight.dataRetrievedAt();
		Long dataAgeMinutes = ageReference == null
				? null
				: Math.max(0, Duration.between(ageReference, serverNow).toMinutes());

		return new FlightTimeContext(
				serverNow,
				serverNow.atOffset(departureOffset),
				departureOffset.getId(),
				boardingTime,
				flight.scheduledDeparture(),
				flight.estimatedDeparture(),
				flight.actualDeparture(),
				effectiveDeparture,
				minutesUntil(serverNow, boardingTime),
				minutesUntil(serverNow, flight.scheduledDeparture()),
				minutesUntil(serverNow, flight.estimatedDeparture()),
				minutesUntil(serverNow, effectiveDeparture),
				dataAgeMinutes,
				flight.lastUpdated(),
				flight.dataRetrievedAt(),
				flight.lastUpdated() != null,
				dataAgeMinutes != null && dataAgeMinutes > STALE_AFTER_MINUTES);
	}

	private ZoneOffset departureOffset(FlightResponse flight, OffsetDateTime boardingTime) {
		OffsetDateTime reference = firstNonNull(
				flight.scheduledDeparture(), flight.estimatedDeparture(), flight.actualDeparture(), boardingTime);
		return reference == null ? ZoneOffset.UTC : reference.getOffset();
	}

	private Long minutesUntil(Instant now, OffsetDateTime target) {
		return target == null ? null : Duration.between(now, target.toInstant()).toMinutes();
	}

	@SafeVarargs
	private final <T> T firstNonNull(T... values) {
		for (T value : values) if (value != null) return value;
		return null;
	}
}
