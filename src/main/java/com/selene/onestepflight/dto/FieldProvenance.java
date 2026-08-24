package com.selene.onestepflight.dto;

import java.time.Instant;
import java.util.List;

public record FieldProvenance(
		String value,
		String provider,
		Instant sourceUpdatedAt,
		Instant retrievedAt,
		boolean live,
		boolean conflict,
		List<FieldCandidate> candidateValues) {
}
