package com.selene.onestepflight.dto;

import java.time.Instant;

public record ApiError(
		String code,
		String messageZh,
		String messageEn,
		Instant timestamp) {
}
