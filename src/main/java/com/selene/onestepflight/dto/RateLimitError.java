package com.selene.onestepflight.dto;

import java.time.Instant;

public record RateLimitError(String code, String messageZh, String messageEn, String messageEs,
		long retryAfterSeconds, Instant occurredAt) {
}
