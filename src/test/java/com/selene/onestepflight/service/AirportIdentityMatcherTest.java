package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class AirportIdentityMatcherTest {
	private final AirportIdentityMatcher matcher = new AirportIdentityMatcher();
	private final AirportIdentity schiphol = new AirportIdentity("AMS", "EHAM", "Amsterdam Airport Schiphol",
			"Amsterdam", "NL", "Netherlands", 52.308601, 4.76389, "large_airport", true, List.of());

	@Test
	void acceptsStrongMapsPlaceWhenOfficialHostDoesNotContainIata() {
		var result = matcher.matchMaps(schiphol, candidate("Amsterdam Airport Schiphol", "Amsterdam Netherlands",
				52.3100, 4.7600, "https://www.schiphol.nl/", List.of("airport")));
		assertThat(result.decision()).isEqualTo(AirportIdentityCandidate.Decision.ACCEPTED);
		assertThat(result.matchedSignals()).anyMatch(value -> value.startsWith("COORDINATES_WITHIN_15KM"));
	}

	@Test
	void rejectsUnrelatedHostContainingIataSubstringAndThirdPartyOfficialClaim() {
		var result = matcher.matchMaps(schiphol, candidate("AMS Cafe", "New York United States",
				40.7, -74.0, "https://ams-cafe.example/official", List.of("cafe", "restaurant")));
		assertThat(result.decision()).isEqualTo(AirportIdentityCandidate.Decision.REJECTED);
		assertThat(result.conflictingSignals()).contains("PLACE_TYPE_NOT_AIRPORT", "ORDINARY_MERCHANT_TYPE",
				"COUNTRY_TEXT_UNRECOGNIZED_OR_DIFFERENT");
	}

	@Test
	void acceptsTitleWithoutIataUsingCanonicalNameAndCoordinates() {
		var result = matcher.matchMaps(schiphol, candidate("Luchthaven Schiphol", "Amsterdam Netherlands",
				52.3086, 4.7639, "https://schiphol.nl", List.of("international airport")));
		assertThat(result.decision()).isEqualTo(AirportIdentityCandidate.Decision.ACCEPTED);
	}

	@Test
	void rejectsAnotherAirportInTheSameCityWhenNameAndCoordinatesDisagree() {
		var result = matcher.matchMaps(schiphol, candidate("Amsterdam Lelystad Airport", "Amsterdam Netherlands",
				52.4603, 5.5272, "https://lelystadairport.nl", List.of("airport")));
		assertThat(result.decision()).isEqualTo(AirportIdentityCandidate.Decision.REJECTED);
		assertThat(result.reason()).contains("COORDINATES_TOO_FAR");
	}

	@Test
	void rejectsOrdinarySameNameMerchant() {
		var result = matcher.matchMaps(schiphol, candidate("Schiphol Office", "Amsterdam Netherlands",
				52.3086, 4.7639, "https://office.example", List.of("office")));
		assertThat(result.decision()).isEqualTo(AirportIdentityCandidate.Decision.REJECTED);
	}

	@Test
	void requestNameCanOnlySupplementButNotOverrideCanonicalIataEntity() {
		assertThat(matcher.requestNameMatches(schiphol, "Amsterdam Airport Schiphol")).isTrue();
		assertThat(matcher.requestNameMatches(schiphol, "John F Kennedy International Airport")).isFalse();
		assertThat(schiphol.withRequestAlias("Amsterdam Airport Schiphol").iataCode()).isEqualTo("AMS");
	}

	@Test
	void httpWebsiteHostParticipatesInIdentityWithoutBecomingASafeDisplayUrl() {
		var result = matcher.matchMaps(schiphol, candidate("Amsterdam Airport Schiphol", "Amsterdam Netherlands",
				52.3086, 4.7639, "http://schiphol.example/", List.of("airport")));
		assertThat(result.decision()).isEqualTo(AirportIdentityCandidate.Decision.ACCEPTED);
		assertThat(result.websiteHost()).isEqualTo("schiphol.example");
		assertThat(OfficialTravelSourcePolicy.safeUri(result.websiteUrl())).isNull();
	}

	@Test
	void countryTextDifferenceCannotRejectStrongNearbyAirportEvidence() {
		AirportIdentity canonical = new AirportIdentity("TST", "TSTA", "Istanbul Airport", "Istanbul",
				"TR", "Turkey", 41.274874, 28.732136, "large_airport", true, List.of("Arnavutkoy"));
		var result = matcher.matchMaps(canonical, candidate("Istanbul Airport",
				"Terminal Caddesi, Istanbul, Türkiye", 41.2761476, 28.7287349,
				"https://airport.example/", List.of("International airport", "Airport")));

		assertThat(result.decision()).isEqualTo(AirportIdentityCandidate.Decision.ACCEPTED);
		assertThat(result.conflictingSignals()).contains("COUNTRY_TEXT_UNRECOGNIZED_OR_DIFFERENT");
		assertThat(result.matchedSignals()).anyMatch(value -> value.startsWith("COORDINATES_WITHIN_15KM:0.32"));
	}

	@Test
	void countryTextDifferenceDoesNotOverrideDistanceOrMerchantHardConflicts() {
		AirportIdentity canonical = new AirportIdentity("TST", "TSTA", "Istanbul Airport", "Istanbul",
				"TR", "Turkey", 41.274874, 28.732136, "large_airport", true, List.of());
		var far = matcher.matchMaps(canonical, candidate("Istanbul Airport", "Another country",
				40.0, 27.0, "https://airport.example/", List.of("Airport")));
		assertThat(far.decision()).isEqualTo(AirportIdentityCandidate.Decision.REJECTED);
		assertThat(far.reason()).contains("COORDINATES_TOO_FAR");

		for (String merchant : List.of("parking", "shop", "hotel")) {
			var nearbyMerchant = matcher.matchMaps(canonical, candidate("Istanbul Airport " + merchant,
					"Istanbul, Türkiye", 41.2761476, 28.7287349,
					"https://merchant.example/", List.of("Airport", merchant)));
			assertThat(nearbyMerchant.decision()).as(merchant)
					.isEqualTo(AirportIdentityCandidate.Decision.REJECTED);
			assertThat(nearbyMerchant.reason()).as(merchant).contains("ORDINARY_MERCHANT_TYPE");
		}
	}

	@Test
	void noCoordinatesAndDifferentCountryTextRemainUnverifiedForInsufficientPositiveEvidence() {
		AirportIdentity canonical = new AirportIdentity("TST", "TSTA", "Istanbul Airport", "Istanbul",
				"TR", "Turkey", 41.274874, 28.732136, "large_airport", true, List.of());
		var result = matcher.matchMaps(canonical, candidate("Istanbul Airport", "Türkiye",
				null, null, "https://airport.example/", List.of("Airport")));

		assertThat(result.decision()).isEqualTo(AirportIdentityCandidate.Decision.REJECTED);
		assertThat(result.reason()).startsWith("INSUFFICIENT_CANONICAL_IDENTITY_SIGNALS")
				.contains("COUNTRY_TEXT_UNRECOGNIZED_OR_DIFFERENT");
	}

	private static AirportIdentityCandidate candidate(String title, String address, Double latitude,
			Double longitude, String website, List<String> types) {
		String host = website.replaceFirst("^https?://(?:www\\.)?", "").replaceFirst("/.*$", "");
		return new AirportIdentityCandidate("test", "GOOGLE_MAPS", "$.place_results", title, address,
				address, latitude, longitude, website, host, "place-id", types, null,
				"https://www.google.com/maps/place/test", List.of(), List.of(), IdentityConfidence.NONE,
				AirportIdentityCandidate.Decision.PENDING, null);
	}
}
