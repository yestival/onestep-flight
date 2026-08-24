package com.selene.onestepflight.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record ProviderFlightResult(
		String provider,
		String outcome,
		String errorCode,
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
		String checkInDeskOrZone,
		String dedicatedBagDropDesk,
		String arrivalBaggageBelt,
		String status,
		Integer departureDelayMinutes,
		Integer arrivalDelayMinutes,
		String quality,
		Instant sourceUpdatedAt,
		Instant retrievedAt,
		String sourceUrl,
		String codeshare,
		boolean live,
		boolean fromCache) {

	public boolean successful() {
		return "SUCCESS".equals(outcome);
	}

	public static ProviderFlightResult failure(String provider, String outcome, String errorCode,
			Instant retrievedAt) {
		return new ProviderFlightResult(
				provider, outcome, errorCode,
				null, null,
				null, null, null, null,
				null, null, null, null, null, null, null,
				null, null, null, null, null,
				null, null, null, null,
				null, retrievedAt, null, null,
				false, false);
	}
}
