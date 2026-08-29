package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JourneyProblemPromptBuilderTest {
	private final JourneyProblemPromptBuilder builder = new JourneyProblemPromptBuilder();

	@ParameterizedTest
	@CsvSource({ "zh,Simplified Chinese", "en,English", "es,Spanish" })
	void localeAndCompleteStatementArePresentInBothPrompts(String locale, String language) {
		String statement = "Keep this complete statement, including BA117 and the onward flight at 21:15.";
		var request = JourneyProblemTestFixtures.request(locale, true, statement);
		assertThat(builder.buildPrimary(request)).contains("selected locale: " + locale, language, statement);
		assertThat(builder.buildRecovery(request)).contains("selected locale: " + locale, language, statement);
	}

	@Test
	void currentFlightFieldsArePassedDirectlyWithoutDerivedSituation() {
		String prompt = builder.buildPrimary(JourneyProblemTestFixtures.request("en", true,
				"My flight is delayed and I may miss my connection."));
		assertThat(prompt).contains(
				"flight number: BA117",
				"departure date: 2026-08-29",
				"departure airport: LHR (London Heathrow)",
				"arrival airport: JFK (New York JFK)",
				"airport where the traveller needs help: LHR (London Heathrow)",
				"scheduled departure: 2026-08-29T16:30+01:00",
				"estimated departure: 2026-08-29T17:30+01:00",
				"scheduled arrival: 2026-08-29T19:30-04:00",
				"estimated arrival: 2026-08-29T20:30-04:00",
				"status: DELAYED",
				"departure delay minutes: 60",
				"current time: 2026-08-29T14:00:00Z",
				"current airport local time: 29 Aug 2026, 15:00",
				"STATUS_CHANGED | SCHEDULED | DELAYED");
		assertThat(prompt).doesNotContain("situation:", "earliest low-stress", "passenger/status conflict");
	}

	@Test
	void historicalFlightFactsAndRuleArePresentInBothPrompts() {
		var request = JourneyProblemTestFixtures.request("en", false,
				"This flight was delayed last month and I need help now.",
				JourneyProblemTestFixtures.historicalDelayedFlight());
		for (String prompt : new String[] { builder.buildPrimary(request), builder.buildRecovery(request) }) {
			assertThat(prompt).contains("departure date: 2026-07-29", "status: DELAYED",
					"current time: 2026-08-29T14:00:00Z", "historical");
			assertThat(prompt).containsIgnoringCase("boarding or delayed today");
		}
	}
}
