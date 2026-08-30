package com.selene.onestepflight.dto;

import java.time.Instant;

public record NextActionResponse(
		JourneyStepType stepType,
		ActionCode actionCode,
		String titleZh,
		String descriptionZh,
		String titleEn,
		String descriptionEn,
		String titleEs,
		String descriptionEs,
		String destination,
		Urgency urgency,
		String reason,
		String triggeredBy,
		String dataSource,
		String reliability,
		Double confidence,
		boolean needsOnSiteConfirmation,
		Instant lastUpdated) {
}
