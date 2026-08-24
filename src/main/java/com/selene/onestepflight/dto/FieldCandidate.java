package com.selene.onestepflight.dto;

import java.time.Instant;

public record FieldCandidate(
		String value,
		String provider,
		Instant sourceUpdatedAt,
		Instant retrievedAt,
		boolean live,
		String quality) {
}
