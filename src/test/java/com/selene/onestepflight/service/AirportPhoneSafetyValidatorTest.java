package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AirportPhoneSafetyValidatorTest {
	private final AirportPhoneSafetyValidator validator = new AirportPhoneSafetyValidator();

	@Test
	void preservesOfficialFormatsAndBuildsSafeDialUris() {
		for (String phone : new String[] { "+99 12 3456 7890", "(012) 345-6789", "0800 123 456", "12345" }) {
			var result = validator.validate(phone);
			assertThat(result.accepted()).as(phone).isTrue();
			assertThat(result.display()).as(phone).isEqualTo(phone);
			assertThat(result.telUri()).as(phone).matches("tel:\\+?\\d{5,18}");
		}
	}

	@Test
	void normalizesFormattingForConflictComparison() {
		assertThat(validator.validate("+99 (12) 3456-7890").normalizedDigits())
				.isEqualTo(validator.validate("+991234567890").normalizedDigits());
	}

	@Test
	void rejectsObviousNonPhoneArtifactsWithoutSearchingForAlternatives() {
		for (String value : new String[] { "2020-2026", "08:00-18:00", "2026/08/29", "AB-123456", "1234" }) {
			assertThat(validator.validate(value).accepted()).as(value).isFalse();
		}
	}
}
