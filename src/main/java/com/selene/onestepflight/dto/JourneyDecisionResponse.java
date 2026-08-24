package com.selene.onestepflight.dto;

public record JourneyDecisionResponse(
		ResolvedFlightContext flight,
		NextActionResponse nextAction) {
}
