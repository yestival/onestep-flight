package com.selene.onestepflight.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record JourneyProblemRequest(
		@NotNull Boolean hasConnection,
		@NotBlank @Size(max = 1000) String passengerStatement,
		@NotBlank @Pattern(regexp = "zh|en|es") String locale,
		@NotNull @Valid CurrentFlight currentFlight) {

	public record CurrentFlight(
			@NotBlank String flightNumber,
			LocalDate departureDate,
			String departureAirport,
			String departureAirportName,
			String arrivalAirport,
			String arrivalAirportName,
			@NotBlank @Pattern(regexp = "[A-Z]{3}") String problemAirport,
			String problemAirportName,
			String problemPhase,
			OffsetDateTime scheduledDeparture,
			OffsetDateTime estimatedDeparture,
			OffsetDateTime actualDeparture,
			OffsetDateTime effectiveDeparture,
			OffsetDateTime scheduledArrival,
			OffsetDateTime estimatedArrival,
			OffsetDateTime actualArrival,
			OffsetDateTime boardingTime,
			String terminal,
			String arrivalTerminal,
			String gate,
			String status,
			Integer delayMinutes,
			Integer arrivalDelayMinutes,
			String codeshare,
			Instant dataLastUpdatedAt,
			Instant dataRetrievedAt,
			Instant currentTime,
			String currentLocalTime,
			List<FlightChangeInput> latestChanges) {

		public CurrentFlight {
			flightNumber = trim(flightNumber);
			departureAirport = airport(departureAirport);
			arrivalAirport = airport(arrivalAirport);
			problemAirport = airport(problemAirport);
			latestChanges = latestChanges == null ? List.of() : List.copyOf(latestChanges);
		}
	}

	public record FlightChangeInput(
			String type,
			String oldValue,
			String newValue,
			Boolean critical,
			Instant detectedAt) { }

	private static String airport(String value) {
		return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
	}

	private static String trim(String value) {
		return value == null ? null : value.trim();
	}
}
