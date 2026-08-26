package com.selene.onestepflight.service.airline;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AirlineResolution(
		String normalizedQuery,
		AirlineMatchType matchType,
		List<AirlineRecord> candidates,
		AirlineResolutionConfidence confidence,
		boolean ambiguous) {

	public AirlineResolution {
		matchType = Objects.requireNonNull(matchType, "matchType");
		candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
		confidence = Objects.requireNonNull(confidence, "confidence");
	}

	public boolean isHighConfidence() {
		return confidence == AirlineResolutionConfidence.HIGH && !ambiguous;
	}

	/**
	 * Returns the sole active candidate when current code reuse is unambiguous,
	 * otherwise the sole candidate of any status.
	 */
	public Optional<AirlineRecord> uniqueCandidate() {
		List<AirlineRecord> active = candidates.stream()
				.filter(candidate -> candidate.status() == AirlineStatus.ACTIVE)
				.toList();
		if (active.size() == 1) return Optional.of(active.get(0));
		return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
	}
}
