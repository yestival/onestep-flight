package com.selene.onestepflight.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record FlightResponse(
		String flightNumber,
		LocalDate departureDate,
		String departureAirport,
		String departureAirportName,
		String arrivalAirport,
		String arrivalAirportName,
		OffsetDateTime scheduledDeparture,
		OffsetDateTime estimatedDeparture,
		OffsetDateTime actualDeparture,
		OffsetDateTime scheduledArrival,
		OffsetDateTime estimatedArrival,
		OffsetDateTime actualArrival,
		OffsetDateTime boardingTime,
		String terminal,
		String gate,
		String checkInCounter,
		String status,
		int delayMinutes,
		int arrivalDelayMinutes,
		Instant lastUpdated,
		Instant dataRetrievedAt,
		String dataSource,
		String sourceUrl,
		String codeshare,
		boolean live,
		boolean checkInCounterNeedsConfirmation,
		boolean demo) {
}
