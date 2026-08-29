package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class AirportContactSelectorTest {
	private final AirportIdentity airport = new AirportIdentity("TST", "KTST", "Test Airport", "Test City",
			"US", "United States", 40, -70, "large_airport", true, List.of());
	private final AirportPhoneNormalizer normalizer = new AirportPhoneNormalizer();
	private final AirportContactSelector selector = new AirportContactSelector(normalizer);

	@Test
	void mapsPhoneSurvivesWhenContactPageCannotBeProvenAndIsNotCrossAssembled() {
		AirportContactCandidate maps = candidate("maps", PageAudience.UNKNOWN, mapsPhone("+1 718 244 4444"),
				null, true, "https://www.google.com/maps/place/test");
		AirportContactCandidate unrelatedPage = candidate("unknown-page", PageAudience.UNKNOWN, null,
				"https://airport.example/contact", true, "https://airport.example/contact");

		var selected = selector.select(List.of(maps, unrelatedPage));

		assertThat(selected.phone().e164()).isEqualTo("+17182444444");
		assertThat(selected.contact()).isNull();
	}

	@Test
	void rootCitationCannotProveDeepLinkButOrganicCandidateCan() {
		AirportContactCandidate rootOnly = candidate("root", PageAudience.PASSENGER_GENERAL, null,
				"https://airport.example/deep/contact", false, "https://airport.example/");
		AirportContactCandidate organic = candidate("organic", PageAudience.PASSENGER_GENERAL, null,
				"https://airport.example/passengers/contact", true,
				"https://airport.example/passengers/contact");

		var selected = selector.select(List.of(rootOnly, organic));

		assertThat(selected.contact()).isEqualTo(organic);
	}

	@Test
	void firstCorporateContactIsRejectedAndLaterPassengerPageWins() {
		AirportContactCandidate corporate = candidate("corporate", PageAudience.CORPORATE_SPECIALIZED, null,
				"https://operator.example/group/strategy/contact", true,
				"https://operator.example/group/strategy/contact");
		AirportContactCandidate passenger = candidate("passenger", PageAudience.PASSENGER_GENERAL, null,
				"https://operator.example/passengers/contact", true,
				"https://operator.example/passengers/contact");
		assertThat(selector.select(List.of(corporate, passenger)).contact()).isEqualTo(passenger);
	}

	@Test
	void verifiedPassengerPageOutranksConflictingMapsPhoneAndRecordsConflict() {
		AirportPhoneCandidate officialPhone = officialPhone("+1 718 244 4444",
				"https://airport.example/passengers/contact");
		AirportContactCandidate official = candidate("official", PageAudience.PASSENGER_GENERAL, officialPhone,
				"https://airport.example/passengers/contact", true,
				"https://airport.example/passengers/contact");
		AirportContactCandidate maps = candidate("maps", PageAudience.UNKNOWN, mapsPhone("+1 212 555 0100"),
				null, true, "https://www.google.com/maps/place/test");

		var selected = selector.select(List.of(maps, official));

		assertThat(selected.phone().e164()).isEqualTo("+17182444444");
		assertThat(selected.conflict()).isTrue();
		assertThat(selected.reasons()).contains("LOWER_TIER_PHONE_CONFLICT");
	}

	@Test
	void weakAiCandidateCannotDeleteStrongMapsPhone() {
		AirportPhoneCandidate ai = normalizer.normalize("+1 212 555 0100", airport,
				ContactRole.GENERAL_PASSENGER, "https://airport.example/contact", "AI",
				PhoneEvidenceTier.AI_BOUND_DISCOVERY);
		var selected = selector.select(List.of(
				candidate("ai", PageAudience.UNKNOWN, ai, null, true, ai.sourceUrl()),
				candidate("maps", PageAudience.UNKNOWN, mapsPhone("+1 718 244 4444"), null, true,
						"https://www.google.com/maps/place/test")));
		assertThat(selected.phone().e164()).isEqualTo("+17182444444");
	}

	@Test
	void equalTierOfficialPassengerSourcesWithRealConflictSafelyReturnNoPhone() {
		var selected = selector.select(List.of(
				candidate("one", PageAudience.PASSENGER_GENERAL, officialPhone("+1 718 244 4444", "https://airport.example/contact-one"),
						"https://airport.example/contact-one", true, "https://airport.example/contact-one"),
				candidate("two", PageAudience.PASSENGER_GENERAL, officialPhone("+1 212 555 0100", "https://airport.example/contact-two"),
						"https://airport.example/contact-two", true, "https://airport.example/contact-two")));
		assertThat(selected.phone()).isNull();
		assertThat(selected.conflict()).isTrue();
	}

	@Test
	void phoneAndContactFromDifferentSourcesAreNotAutomaticallyPaired() {
		AirportContactCandidate phone = candidate("phone", PageAudience.UNKNOWN, mapsPhone("+1 718 244 4444"),
				null, true, "https://www.google.com/maps/place/test");
		AirportContactCandidate page = candidate("page", PageAudience.PASSENGER_GENERAL,
				officialPhone("+1 212 555 0100", "https://airport.example/different-source"), "https://airport.example/contact", true,
				"https://airport.example/contact");
		var selected = selector.select(List.of(phone, page));
		assertThat(selected.phone().e164()).isEqualTo("+17182444444");
		assertThat(selected.contact()).isNull();
	}

	private AirportContactCandidate candidate(String id, PageAudience audience, AirportPhoneCandidate phone,
			String contactUrl, boolean bound, String sourceUrl) {
		return new AirportContactCandidate(id, airport.canonicalId(), "Test Airport", audience,
				ContactRole.GENERAL_PASSENGER, phone, contactUrl, sourceUrl, id, "TEST",
				List.of(new ResourceEvidence("TEST", sourceUrl, sourceUrl, bound, true, List.of())),
				IdentityConfidence.HIGH);
	}

	private AirportPhoneCandidate mapsPhone(String value) {
		return normalizer.normalize(value, airport, ContactRole.GENERAL_PASSENGER,
				"https://www.google.com/maps/place/test", "GOOGLE_MAPS",
				PhoneEvidenceTier.VERIFIED_STRUCTURED_MAPS);
	}

	private AirportPhoneCandidate officialPhone(String value, String sourceUrl) {
		return normalizer.normalize(value, airport, ContactRole.GENERAL_PASSENGER,
				sourceUrl, "OFFICIAL_PAGE",
				PhoneEvidenceTier.VERIFIED_PASSENGER_PAGE);
	}
}
