package com.selene.onestepflight.dto;

import java.time.Instant;
import java.util.Map;

public record ProviderAggregateError(
		String code,
		String messageZh,
		String messageEn,
		Map<String, ProviderFlightResult> providerResults,
		Instant timestamp) {
}
