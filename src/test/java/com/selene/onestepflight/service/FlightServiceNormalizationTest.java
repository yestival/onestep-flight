package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FlightServiceNormalizationTest {

	private final FlightService service = new FlightService(null, null);

	@Test
	void acceptsTheOptionalTrailingLetterAllowedByDocumentExtraction() {
		assertThat(service.normalize("ba 117a")).isEqualTo("BA117A");
		assertThat(service.normalize("U2-1234Z")).isEqualTo("U21234Z");
	}

	@Test
	void stillRejectsValuesOutsideThePrivacySafeFlightDesignatorFormat() {
		assertThatThrownBy(() -> service.normalize("BA117AB"))
				.isInstanceOf(InvalidFlightNumberException.class);
		assertThatThrownBy(() -> service.normalize("PRIVATEPNR"))
				.isInstanceOf(InvalidFlightNumberException.class);
	}
}
