package com.selene.onestepflight.dto;

import java.time.Instant;

public record FlightFieldConflict(
		String field,
		String documentValue,
		String liveValue,
		boolean blocking,
		String messageZh,
		String messageEn,
		Instant detectedAt) {
}
