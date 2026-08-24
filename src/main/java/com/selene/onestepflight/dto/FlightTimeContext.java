package com.selene.onestepflight.dto;

import java.time.Instant;
import java.time.OffsetDateTime;

public record FlightTimeContext(
		Instant serverNow,
		OffsetDateTime departureLocalNow,
		String departureUtcOffset,
		OffsetDateTime boardingTime,
		OffsetDateTime scheduledDeparture,
		OffsetDateTime estimatedDeparture,
		OffsetDateTime actualDeparture,
		OffsetDateTime effectiveDeparture,
		Long minutesUntilBoarding,
		Long minutesUntilScheduledDeparture,
		Long minutesUntilEstimatedDeparture,
		Long minutesUntilEffectiveDeparture,
		Long dataAgeMinutes,
		Instant dataLastUpdatedAt,
		Instant dataRetrievedAt,
		boolean sourceUpdatedAtAvailable,
		boolean stale) {
}
