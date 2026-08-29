package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class AirportPhoneNormalizerTest {
	private final AirportPhoneNormalizer normalizer = new AirportPhoneNormalizer();
	private final AirportIdentity haneda = airport("HND", "JP");
	private final AirportIdentity jfk = airport("JFK", "US");
	private final AirportIdentity cdg = airport("CDG", "FR");

	@Test
	void japaneseNationalAndInternationalFormatsAreEquivalent() {
		var national = phone("03-5757-8111", haneda);
		var international = phone("+81 3-5757-8111", haneda);
		assertThat(normalizer.compare(national, international)).isEqualTo(PhoneEquivalence.EQUIVALENT);
		assertThat(national.e164()).isEqualTo("+81357578111");
		assertThat(national.telUri()).isEqualTo("tel:+81357578111");
	}

	@Test
	void northAmericanNationalAndInternationalFormatsAreEquivalent() {
		var national = phone("(718) 244-4444", jfk);
		var international = phone("+1 (718) 244-4444", jfk);
		assertThat(normalizer.compare(national, international)).isEqualTo(PhoneEquivalence.EQUIVALENT);
		assertThat(national.telUri()).isEqualTo("tel:+17182444444");
	}

	@Test
	void verifiedPassengerPageCanProvideNationalShortCode() {
		var accepted = normalizer.normalizeShortCode("3950", cdg, ContactRole.GENERAL_PASSENGER,
				"https://www.parisaeroport.fr/passagers/contact", "OFFICIAL_PAGE",
				PhoneEvidenceTier.VERIFIED_PASSENGER_PAGE, true);
		var rejected = normalizer.normalizeShortCode("2026", cdg, ContactRole.UNKNOWN,
				"https://directory.example/contact", "SNIPPET", PhoneEvidenceTier.UNVERIFIED, false);
		assertThat(accepted.accepted()).isTrue();
		assertThat(accepted.scope()).isEqualTo(PhoneScope.NATIONAL_SHORT_CODE);
		assertThat(rejected.accepted()).isFalse();
	}

	@Test
	void nationalShortCodeAndInternationalPassengerNumberAreCompatibleAlternatives() {
		var shortCode = normalizer.normalizeShortCode("3950", cdg, ContactRole.PASSENGER_INFORMATION,
				"https://www.parisaeroport.fr/contact", "OFFICIAL_PAGE",
				PhoneEvidenceTier.VERIFIED_PASSENGER_PAGE, true);
		var international = phone("+33 1 70 36 39 50", cdg);
		assertThat(normalizer.compare(shortCode, international))
				.isEqualTo(PhoneEquivalence.COMPATIBLE_DIFFERENT_SCOPE);
	}

	@Test
	void trulyDifferentNumbersConflictButInvalidCandidateIsUnknown() {
		var first = phone("+1 718 244 4444", jfk);
		var second = phone("+1 212 555 0100", jfk);
		var invalid = normalizer.normalize("2026", jfk, ContactRole.UNKNOWN, null, "AI",
				PhoneEvidenceTier.UNVERIFIED);
		assertThat(normalizer.compare(first, second)).isEqualTo(PhoneEquivalence.CONFLICT);
		assertThat(normalizer.compare(first, invalid)).isEqualTo(PhoneEquivalence.UNKNOWN);
	}

	private AirportPhoneCandidate phone(String value, AirportIdentity airport) {
		return normalizer.normalize(value, airport, ContactRole.GENERAL_PASSENGER,
				"https://official.example/contact", "OFFICIAL_PAGE", PhoneEvidenceTier.VERIFIED_PASSENGER_PAGE);
	}

	private static AirportIdentity airport(String code, String country) {
		return new AirportIdentity(code, null, code + " Airport", "City", country,
				country, 0, 0, "large_airport", true, List.of());
	}
}
