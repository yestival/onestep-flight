package com.selene.onestepflight.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record JourneyContext(
		@NotBlank String flightNumber,
		@NotNull LocalDate departureDate,
		@NotNull ResolvedFlightContext flightSnapshot,
		boolean demo,
		String demoScenario,
		String documentType,
		boolean boardingPassDetected,
		boolean checkedIn,
		Boolean hasCheckedBaggage,
		boolean bagDropped,
	boolean securityCleared,
	boolean atGate,
	boolean boarded,
	boolean landed,
	boolean baggageCollected,
	boolean exitedAirport,
	boolean needsAssistance,
		String previousTerminal,
		String previousGate,
		boolean conflictsConfirmed,
		boolean refreshRequested) {
}
