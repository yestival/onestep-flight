package com.selene.onestepflight.dto;

import java.time.Instant;

public record FlightChange(
		String type,
		String oldValue,
		String newValue,
		boolean critical,
		String titleZh,
		String titleEn,
		Instant detectedAt) {
}
