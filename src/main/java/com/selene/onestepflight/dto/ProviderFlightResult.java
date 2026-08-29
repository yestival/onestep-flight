package com.selene.onestepflight.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;

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
		String arrivalTerminal,
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

	public static final String SUCCESS = "SUCCESS";
	public static final String NOT_FOUND = "NOT_FOUND";
	public static final String IDENTITY_UNVERIFIED = "IDENTITY_UNVERIFIED";
	public static final String IDENTITY_MISMATCH = "IDENTITY_MISMATCH";
	public static final String TIMEOUT = "TIMEOUT";
	public static final String AUTH_ERROR = "AUTH_ERROR";
	public static final String RATE_LIMITED = "RATE_LIMITED";
	public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
	public static final String PARSE_ERROR = "PARSE_ERROR";
	public static final String UPSTREAM_ERROR = "UPSTREAM_ERROR";

	private static final Set<String> ALLOWED_OUTCOMES = Set.of(
			SUCCESS, NOT_FOUND, IDENTITY_UNVERIFIED, IDENTITY_MISMATCH, TIMEOUT,
			AUTH_ERROR, RATE_LIMITED, NOT_CONFIGURED, PARSE_ERROR, UPSTREAM_ERROR);

	public ProviderFlightResult {
		if (!ALLOWED_OUTCOMES.contains(outcome)) {
			throw new IllegalArgumentException("Unsupported provider outcome: " + outcome);
		}
	}

	public boolean successful() {
		return SUCCESS.equals(outcome);
	}

	public static ProviderFlightResult failure(String provider, String outcome, String errorCode,
			Instant retrievedAt) {
		return new ProviderFlightResult(
				provider, outcome, errorCode,
				null, null,
				null, null, null, null,
				null, null, null, null, null, null, null,
				null, null, null, null, null, null,
				null, null, null, null,
				null, retrievedAt, null, null,
				false, false);
	}

	public static ProviderFlightResult identityFailure(String provider, String outcome, String errorCode,
			String actualFlightNumber, LocalDate actualDepartureDate, Instant retrievedAt) {
		if (!IDENTITY_UNVERIFIED.equals(outcome) && !IDENTITY_MISMATCH.equals(outcome)) {
			throw new IllegalArgumentException("Identity failure requires an identity outcome");
		}
		return new ProviderFlightResult(
				provider, outcome, errorCode, actualFlightNumber, actualDepartureDate,
				null, null, null, null,
				null, null, null, null, null, null, null,
				null, null, null, null, null, null,
				null, null, null, null,
				null, retrievedAt, null, null,
				false, false);
	}
}
