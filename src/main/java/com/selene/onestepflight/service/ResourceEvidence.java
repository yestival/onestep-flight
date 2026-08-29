package com.selene.onestepflight.service;

import java.util.List;

public record ResourceEvidence(String sourceType, String sourceUrl, String citationUrl,
		boolean candidateLevelBound, boolean organicCandidate, List<String> signals) {
	public ResourceEvidence { signals = signals == null ? List.of() : List.copyOf(signals); }
}
