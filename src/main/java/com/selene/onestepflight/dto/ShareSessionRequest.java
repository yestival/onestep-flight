package com.selene.onestepflight.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Privacy-minimized snapshot sent to a family journey share. It deliberately
 * contains no traveller identity, reservation reference, seat, barcode or raw
 * document data.
 */
public record ShareSessionRequest(
		@NotBlank @Pattern(regexp = "[A-Za-z0-9 ]{2,12}") String flightNumber,
		@NotNull LocalDate departureDate,
		@NotBlank @Pattern(regexp = "[A-Za-z]{3}") String departureAirport,
		@NotBlank @Pattern(regexp = "[A-Za-z]{3}") String arrivalAirport,
		ActionCode actionCode,
		Urgency urgency,
		@Size(max = 80) String destination,
		@Size(max = 12) Set<@NotNull ActionCode> completedSteps,
		@Size(max = 40) String terminal,
		@Size(max = 40) String gate,
		@Size(max = 80) String checkInDeskOrZone,
		@Size(max = 80) String dedicatedBagDropDesk,
		@Size(max = 40) String arrivalBaggageBelt,
		@Size(max = 40) String status,
		Instant lastUpdated,
		boolean completed) {

	@AssertTrue(message = "An active shared journey needs an action and urgency")
	public boolean hasActionWhileActive() {
		return completed || (actionCode != null && urgency != null);
	}

	/**
	 * Share snapshots are deliberately a closed, privacy-minimized contract. An
	 * any-setter makes unknown fields fail even when the application's general
	 * Jackson configuration is permissive.
	 */
	@JsonAnySetter
	public void rejectUnknownField(String fieldName, Object ignoredValue) {
		throw new IllegalArgumentException("Unknown share-session field");
	}
}
