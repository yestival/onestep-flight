package com.selene.onestepflight.service;

import java.util.List;

/** Lossless identity candidate retained until unified reconciliation. */
public record AirportIdentityCandidate(String candidateId, String source, String sourceJsonPath,
		String title, String address, String country, Double latitude, Double longitude,
		String websiteUrl, String websiteHost, String placeId, List<String> placeTypes,
		String rawPhone, String mapsUrl, List<String> matchedSignals, List<String> conflictingSignals,
		IdentityConfidence confidence, Decision decision, String reason) {
	public AirportIdentityCandidate {
		placeTypes = placeTypes == null ? List.of() : List.copyOf(placeTypes);
		matchedSignals = matchedSignals == null ? List.of() : List.copyOf(matchedSignals);
		conflictingSignals = conflictingSignals == null ? List.of() : List.copyOf(conflictingSignals);
		confidence = confidence == null ? IdentityConfidence.NONE : confidence;
		decision = decision == null ? Decision.PENDING : decision;
	}

	public AirportIdentityCandidate decided(List<String> matches, List<String> conflicts,
			IdentityConfidence decidedConfidence, Decision decidedDecision, String decidedReason) {
		return new AirportIdentityCandidate(candidateId, source, sourceJsonPath, title, address, country,
				latitude, longitude, websiteUrl, websiteHost, placeId, placeTypes, rawPhone, mapsUrl,
				matches, conflicts, decidedConfidence, decidedDecision, decidedReason);
	}

	public enum Decision { PENDING, ACCEPTED, VALID_NOT_SELECTED, REJECTED }
}
