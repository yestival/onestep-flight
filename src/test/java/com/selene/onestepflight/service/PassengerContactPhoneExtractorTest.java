package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PassengerContactPhoneExtractorTest {
	private final PassengerContactPhoneExtractor extractor = new PassengerContactPhoneExtractor();

	@Test
	void extractsOnlyLongPhoneBoundToGeneralContactRoleInSameClause() {
		assertThat(extractor.extract("Contact us",
				"Call us Airport contact centre: 0344 335 1801. Accessibility textphone: 0333 200 7459"))
				.containsExactly("0344 335 1801");
	}

	@Test
	void doesNotTreatUnboundDigitsOrShortCodesAsPassengerPhones() {
		assertThat(extractor.extract("Passenger terminal", "Open 24 hours. Flight line 3950."))
				.isEmpty();
	}
}
