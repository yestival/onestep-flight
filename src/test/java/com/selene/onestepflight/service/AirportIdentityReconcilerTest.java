package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AirportIdentityReconcilerTest {
	@Test
	void sameSearchAndStrongMapsHostCrossConfirmsTheDomain() {
		AirportIdentity airport = new AirportIdentity("TST", "TEST", "Test City Airport", "Test City",
				"US", "United States", 40, -70, "large_airport", true, List.of());
		AirportIdentityCandidate place = new AirportIdentityCandidate("maps", "GOOGLE_MAPS", "$.place_results",
				"Test City Airport", "Test City United States", "United States", 40.0, -70.0,
				"https://operator.example", "operator.example", "pid", List.of("airport"), null,
				"https://www.google.com/maps/place/test", List.of("PLACE_TYPE_AIRPORT"), List.of(),
				IdentityConfidence.VERY_HIGH, AirportIdentityCandidate.Decision.ACCEPTED, "CANONICAL_AIRPORT_MATCH");
		var maps = new SerpApiGoogleMapsAirportPlaceParser.Result(Set.of("operator.example"), place.title(),
				place.address(), place.country(), null, null, null, place.websiteUrl(), place.mapsUrl(), true,
				false, false, false, place, List.of(place),
				List.of(new DomainEvidence("operator.example", place.websiteUrl(),
						DomainAuthority.VERIFIED_STRUCTURED_PLACE, "GOOGLE_MAPS", airport.canonicalId(), List.of())), null);
		DomainEvidence provisional = new DomainEvidence("operator.example", "https://operator.example/contact",
				DomainAuthority.PROVISIONAL_SEARCH, "GOOGLE_SEARCH", airport.canonicalId(), List.of("TEXT_MATCH"));

		IdentityDecision decision = new AirportIdentityReconciler().reconcile(airport, List.of(provisional),
				List.of(), maps, false, false);

		assertThat(decision.status()).isEqualTo(IdentityStatus.VERIFIED);
		assertThat(decision.domains()).extracting(DomainEvidence::authority)
				.contains(DomainAuthority.VERIFIED_STRUCTURED_PLACE, DomainAuthority.VERIFIED_CROSS_SOURCE);
	}
}
