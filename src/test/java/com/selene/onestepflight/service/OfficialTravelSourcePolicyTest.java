package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class OfficialTravelSourcePolicyTest {
	private final OfficialTravelSourcePolicy policy = new OfficialTravelSourcePolicy();

	@Test
	void acceptsVerifiedHomepageAndContactSubdomainWithoutEnglishAirportWords() {
		Set<String> domains = Set.of("example-airport.test");
		assertThat(policy.validateAirport("https://www.example-airport.test/passengers",
				"Passenger information", "Example Airport", "", "EXM", "Example International Airport", domains))
				.isPresent();
		assertThat(policy.validateAirport("https://help.example-airport.test/contact",
				"旅客联系", "Example Airport", "", "EXM", "Example International Airport", domains))
				.isPresent();
		assertThat(policy.validateRelatedAirportUrl("https://help.example-airport.test/contact", domains))
				.isPresent();
		assertThat(policy.validateRelatedAirportUrl("https://example-airport.test:443/contact", domains))
				.isPresent();
		assertThat(policy.validateRelatedAirportUrl("http://example-airport.test/maps/terminal-1", domains))
				.hasValueSatisfying(uri -> assertThat(uri.toString())
						.isEqualTo("https://example-airport.test/maps/terminal-1"));
	}

	@Test
	void rejectsUnsafeAndThirdPartyHostsEvenIfPassedAsVerifiedInput() {
		for (String url : new String[] {
				"https://maps.google.com/example", "https://tripadvisor.com/example-airport",
				"https://airport-information.com/exm", "http://example-airport.test/contact",
				"https://user@example-airport.test/contact", "https://example-airport.test:8443/contact" }) {
			assertThat(policy.validateAirport(url, "Example International Airport", "Airport", "",
					"EXM", "Example International Airport", Set.of(host(url)))).as(url).isEmpty();
		}
	}

	@Test
	void airlineMentionInSupportingTextDoesNotInvalidateAnAirportWebsite() {
		assertThat(policy.validateAirport("https://www.example-airport.test/contact",
				"Passenger information", "Example Airport",
				"Airlines use this terminal; call the passenger information desk.",
				"EXM", "Example International Airport", Set.of("example-airport.test"))).isPresent();
		assertThat(policy.validateAirport("https://www.example-airport.test/contact",
				"Example Airways contact", "Example Airways", "EXM airport",
				"EXM", "Example International Airport", Set.of("example-airport.test"))).isEmpty();
	}

	@Test
	void operatorDomainRequiresResourceEvidenceForTheRequestedAirport() {
		Set<String> domains = Set.of("metro-aviation-authority.test");
		assertThat(policy.validateAirport("https://metro-aviation-authority.test/passengers/contact",
				"EXM passenger contact", "Metro Aviation Authority", "Example International Airport",
				"EXM", "Example International Airport", domains)).isPresent();
		assertThat(policy.validateAirport("https://metro-aviation-authority.test/other-airport/news",
				"Other Regional Airport news", "Metro Aviation Authority", "Other airport terminal",
				"EXM", "Example International Airport", domains)).isEmpty();
	}

	@Test
	void acceptsADirectAiCitationFromAnAirportIdentifyingDomainWithoutWeakeningBlockedHosts() {
		assertThat(policy.validateCitedAirport("https://str-airport.test/yolcu/iletisim",
				"Yolcu İletişim", "STR Airport official site", "Starport International Airport",
				"STR", "Starport International Airport", Set.of("operator.test"))).isPresent();
		assertThat(policy.validateCitedAirport("https://airport-information.com/str/contact",
				"Starport Airport contact", "Directory", "Official airport phone",
				"STR", "Starport International Airport", Set.of("operator.test"))).isEmpty();
	}

	private static String host(String url) {
		try {
			String host = java.net.URI.create(url).getHost();
			return host == null ? "invalid.test" : host;
		} catch (IllegalArgumentException exception) {
			return "invalid.test";
		}
	}
}
