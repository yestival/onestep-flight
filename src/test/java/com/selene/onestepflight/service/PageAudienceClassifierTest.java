package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PageAudienceClassifierTest {
	private final PageAudienceClassifier classifier = new PageAudienceClassifier();

	@Test
	void acceptsGeneralPassengerEnquiriesButRejectsCorporateAndSpecializedPages() {
		assertThat(classifier.classify("https://airport.example/contact", "Passenger contact centre",
				"General enquiries and feedback", true)).isEqualTo(PageAudience.PASSENGER_GENERAL);
		for (String text : new String[] { "B2B IT and telecommunications", "Careers and recruitment",
				"Media and press", "Investor relations", "Advertising and procurement" }) {
			assertThat(classifier.classify("https://airport.example/contact", text, text, true))
					.as(text).isEqualTo(PageAudience.CORPORATE_SPECIALIZED);
		}
		assertThat(classifier.classify("https://airport.example/contact", "Lost property", "Baggage help", true))
				.isEqualTo(PageAudience.PASSENGER_SPECIALIZED);
	}

	@Test
	void contactWordAloneDoesNotProvePassengerAudienceAndUnverifiedDomainIsThirdParty() {
		assertThat(classifier.classify("https://airport.example/contact", "Contact", null, true))
				.isEqualTo(PageAudience.UNKNOWN);
		assertThat(classifier.classify("https://directory.example/airport", "Passenger contact", "General enquiries", false))
				.isEqualTo(PageAudience.THIRD_PARTY_DIRECTORY);
	}

	@Test
	void passengerTerminalHomepageIsNotAContactPageWithoutContactIntent() {
		assertThat(classifier.classify("https://airport.example/en/index.html",
				"Airport Passenger Terminal", "Flights, shops and terminal facilities", true))
				.isEqualTo(PageAudience.UNKNOWN);
		assertThat(classifier.classify("https://airport.example/en/contact/index.html",
				"Passenger contact", "Questions and feedback for airport passengers", true))
				.isEqualTo(PageAudience.PASSENGER_GENERAL);
	}
}
