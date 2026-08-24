package com.selene.onestepflight.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.selene.onestepflight.dto.FieldProvenance;
import com.selene.onestepflight.dto.FlightFieldConflict;
import com.selene.onestepflight.dto.FlightResponse;
import com.selene.onestepflight.dto.ProviderFlightResult;

public record MultiSourceFlightSnapshot(
		FlightResponse flight,
		String dedicatedBagDropDesk,
		String arrivalBaggageBelt,
		Map<String, ProviderFlightResult> providerResults,
		Map<String, FieldProvenance> fieldProvenance,
		List<FlightFieldConflict> providerConflicts,
		Instant retrievedAt,
		Instant freshestSourceUpdatedAt) {
}
