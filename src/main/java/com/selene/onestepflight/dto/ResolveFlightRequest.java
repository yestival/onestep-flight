package com.selene.onestepflight.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ResolveFlightRequest(
		@NotBlank String flightNumber,
		@NotNull LocalDate departureDate,
		boolean demo,
		String demoScenario,
		String demoSessionId,
		BoardingPassExtractionResponse document,
		Integer documentSegmentIndex,
		boolean refresh) {

	/**
	 * Keeps callers that predate explicit multi-segment selection source-compatible.
	 * A missing index retains the resolver's existing identity-based selection rules.
	 */
	public ResolveFlightRequest(String flightNumber, LocalDate departureDate, boolean demo,
			String demoScenario, BoardingPassExtractionResponse document, boolean refresh) {
		this(flightNumber, departureDate, demo, demoScenario, null, document, null, refresh);
	}

	public ResolveFlightRequest(String flightNumber, LocalDate departureDate, boolean demo,
			String demoScenario, BoardingPassExtractionResponse document,
			Integer documentSegmentIndex, boolean refresh) {
		this(flightNumber, departureDate, demo, demoScenario, null, document, documentSegmentIndex, refresh);
	}
}
