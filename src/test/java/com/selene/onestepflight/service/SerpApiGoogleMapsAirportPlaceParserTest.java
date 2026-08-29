package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class SerpApiGoogleMapsAirportPlaceParserTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private final AirportIdentity jfk = new AirportIdentityCatalog(mapper).find("JFK").orElseThrow();
	private final SerpApiGoogleMapsAirportPlaceParser parser = new SerpApiGoogleMapsAirportPlaceParser(
			new OfficialTravelSourcePolicy(), new AirportIdentityMatcher(), new AirportPhoneNormalizer());

	@Test
	void exactAirportNeedsNoIataInTitleAndNormalizesMapsPhoneToE164() {
		var result = parser.parse(mapper.readTree("""
				{"search_metadata":{"status":"Success"},"place_results":{
				 "title":"John F. Kennedy International Airport","types":["International airport","Airport"],
				 "address":"Queens, NY, United States","country":"United States",
				 "gps_coordinates":{"latitude":40.6446161,"longitude":-73.7797222},
				 "website":"https://www.jfkairport.com/","phone":"(718) 244-4444",
				 "place_id":"ChIJR0lA1VBmwokR8BGfSBOyT-w"}}
				"""), jfk);

		assertThat(result.matchedAirport()).isTrue();
		assertThat(result.telUri()).isEqualTo("tel:+17182444444");
		assertThat(result.domainEvidence()).singleElement()
				.extracting(DomainEvidence::authority).isEqualTo(DomainAuthority.VERIFIED_STRUCTURED_PLACE);
	}

	@Test
	void httpWebsiteHostParticipatesInIdentityButUrlIsNotSafeForDisplay() {
		var result = parser.parse(mapper.readTree("""
				{"search_metadata":{"status":"Success"},"place_results":{
				 "title":"John F. Kennedy International Airport","type":"airport",
				 "gps_coordinates":{"latitude":40.6446,"longitude":-73.7797},
				 "website":"http://airport-operator.example/","place_id":"ChIJR0lA1VBmwokR8BGfSBOyT-w"}}
				"""), jfk);

		assertThat(result.matchedAirport()).isTrue();
		assertThat(result.domains()).containsExactly("airport-operator.example");
		assertThat(result.websiteUrl()).startsWith("http://");
		assertThat(result.mapsLocationUrl()).startsWith("https://www.google.com/maps/");
	}

	@Test
	void merchantAndSameCityDifferentAirportRemainRetainedAsRejectedCandidates() {
		var result = parser.parse(mapper.readTree("""
				{"search_metadata":{"status":"Success"},"local_results":[
				 {"title":"JFK Airport Cafe","types":["Cafe"],"country":"United States",
				  "gps_coordinates":{"latitude":40.64,"longitude":-73.78},"website":"https://cafe.example/"},
				 {"title":"LaGuardia Airport","types":["Airport"],"country":"United States",
				  "gps_coordinates":{"latitude":40.7769,"longitude":-73.8740},"website":"https://airport.example/"}
				]}
				"""), jfk);

		assertThat(result.matchedAirport()).isFalse();
		assertThat(result.candidates()).hasSize(2).allSatisfy(candidate ->
				assertThat(candidate.decision()).isEqualTo(AirportIdentityCandidate.Decision.REJECTED));
		assertThat(result.candidates()).extracting(AirportIdentityCandidate::reason)
				.anyMatch(reason -> reason.contains("ORDINARY_MERCHANT_TYPE"))
				.anyMatch(reason -> reason.contains("COORDINATES_TOO_FAR")
						|| reason.contains("INSUFFICIENT_CANONICAL_IDENTITY_SIGNALS"));
	}

	@Test
	void nearbyCanonicalAirportSurvivesCountryNameVariantAsSoftEvidence() throws Exception {
		AirportIdentity canonical = new AirportIdentity("TST", "TSTA", "Istanbul Airport", "Istanbul",
				"TR", "Turkey", 41.274874, 28.732136, "large_airport", true, java.util.List.of());
		tools.jackson.databind.JsonNode fixture;
		try (InputStream stream = getClass().getResourceAsStream(
				"/fixtures/airport-resources-ist-country-variant-maps.json")) {
			assertThat(stream).isNotNull();
			fixture = mapper.readTree(stream);
		}
		var result = parser.parse(fixture, canonical);

		assertThat(result.matchedAirport()).isTrue();
		assertThat(result.telUri()).isEqualTo("tel:+904441442");
		assertThat(result.selectedPlace().conflictingSignals())
				.contains("COUNTRY_TEXT_UNRECOGNIZED_OR_DIFFERENT");
	}
}
