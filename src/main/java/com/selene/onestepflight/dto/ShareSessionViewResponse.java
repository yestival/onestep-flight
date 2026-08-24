package com.selene.onestepflight.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

public record ShareSessionViewResponse(
		String flightNumber,
		LocalDate departureDate,
		String departureAirport,
		String arrivalAirport,
		ActionCode actionCode,
		Urgency urgency,
		String destination,
		Set<ActionCode> completedSteps,
		String terminal,
		String gate,
		String checkInDeskOrZone,
		String dedicatedBagDropDesk,
		String arrivalBaggageBelt,
		String status,
		Instant lastUpdated,
		boolean completed,
		Instant expiresAt) {
}
