package com.selene.onestepflight.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ResolveFlightRequest(
		@NotBlank String flightNumber,
		@NotNull LocalDate departureDate,
		boolean demo,
		String demoScenario,
		BoardingPassExtractionResponse document,
		boolean refresh) {
}
