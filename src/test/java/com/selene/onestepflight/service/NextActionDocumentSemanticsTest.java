package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.JourneyContext;

class NextActionDocumentSemanticsTest {

	private final NextActionService service = new NextActionService();

	@Test
	void onlyTrustedBoardingPassSignalSkipsCheckIn() {
		assertThat(service.isEffectivelyCheckedIn(context("BOARDING_PASS", true, false))).isTrue();
		assertThat(service.isEffectivelyCheckedIn(context("BOARDING_PASS", false, false))).isFalse();
		assertThat(service.isEffectivelyCheckedIn(context("ITINERARY", true, false))).isFalse();
		assertThat(service.isEffectivelyCheckedIn(context("E_TICKET", true, false))).isFalse();
		assertThat(service.isEffectivelyCheckedIn(context("BOOKING_CONFIRMATION", true, false))).isFalse();
		assertThat(service.isEffectivelyCheckedIn(context("UNKNOWN", true, false))).isFalse();
	}

	@Test
	void explicitUserCheckInStillCountsForEveryDocumentType() {
		assertThat(service.isEffectivelyCheckedIn(context("UNKNOWN", false, true))).isTrue();
	}

	private JourneyContext context(String documentType, boolean boardingPassDetected, boolean checkedIn) {
		return new JourneyContext("BA117", LocalDate.of(2026, 8, 24), null, false, null,
				documentType, boardingPassDetected, checkedIn, null, false, false, false, false,
				false, false, false, false, null, null, false, false);
	}
}
