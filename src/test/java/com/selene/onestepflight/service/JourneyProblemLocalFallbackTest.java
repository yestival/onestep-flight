package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JourneyProblemLocalFallbackTest {
	private final JourneyProblemLocalFallback fallback = new JourneyProblemLocalFallback();

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"zh|先别着急，请保留登机牌和航班信息，并把你刚才说明的情况告诉航空公司工作人员，请他们协助确认下一步安排。",
			"en|Please don’t worry. Keep your boarding pass and flight details, and explain the situation you described to airline staff so they can help confirm the next step.",
			"es|No te preocupes. Conserva tu tarjeta de embarque y los datos del vuelo, y explica al personal de la aerolínea la situación que has descrito para que pueda confirmar el siguiente paso."
	})
	void oneExactFallbackPerLocale(String locale, String expected) {
		assertThat(fallback.answer(JourneyProblemTestFixtures.request(locale, false, "Please help.")))
				.isEqualTo(expected);
	}
}
