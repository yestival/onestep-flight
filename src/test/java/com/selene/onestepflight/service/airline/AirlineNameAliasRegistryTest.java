package com.selene.onestepflight.service.airline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AirlineNameAliasRegistryTest {

	private final AirlineRegistry registry = AirlineRegistry.loadDefault();

	@ParameterizedTest
	@CsvSource({
			"国航, CA",
			"British Airways, BA",
			"Lufthansa, LH",
			"Emirates, EK",
			"Delta Air Lines, DL",
			"Qantas, QF",
			"新加坡航空, SQ",
			"埃塞俄比亚航空, ET"
	})
	void curatedGlobalDisplayAliasesResolveUniquelyOffline(String name, String expectedIata) {
		AirlineResolution resolution = registry.searchDisplayName(name);

		assertThat(resolution.ambiguous()).isFalse();
		assertThat(resolution.uniqueCandidate().map(AirlineRecord::iata)).contains(expectedIata);
	}
}
