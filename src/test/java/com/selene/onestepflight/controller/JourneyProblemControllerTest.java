package com.selene.onestepflight.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import com.selene.onestepflight.dto.JourneyProblemResponse;
import com.selene.onestepflight.dto.JourneyProblemResponse.AnswerStatus;
import com.selene.onestepflight.service.SerpApiJourneyProblemService;

import jakarta.validation.Validation;

class JourneyProblemControllerTest {
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		SerpApiJourneyProblemService service = mock(SerpApiJourneyProblemService.class);
		when(service.solve(any())).thenReturn(new JourneyProblemResponse("请前往值机柜台。",
				AnswerStatus.ANSWER, Instant.parse("2026-08-29T00:00:00Z")));
		mockMvc = MockMvcBuilders.standaloneSetup(new JourneyProblemController(service))
				.setValidator(new SpringValidatorAdapter(Validation.buildDefaultValidatorFactory().getValidator()))
				.build();
	}

	@Test
	void validRequestReturnsAnswerAndStatus() throws Exception {
		mockMvc.perform(post("/api/assistance/journey-problem").contentType(MediaType.APPLICATION_JSON)
				.content(validBody("zh", "航班延误了")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("请前往值机柜台。"))
				.andExpect(jsonPath("$.status").value("ANSWER"));
	}

	@Test void blankStatementReturns400() throws Exception { expectBadRequest(validBody("zh", " ")); }
	@Test void unsupportedLocaleReturns400() throws Exception { expectBadRequest(validBody("fr", "Delayed")); }
	@Test void missingCurrentFlightReturns400() throws Exception {
		expectBadRequest("{\"hasConnection\":false,\"passengerStatement\":\"延误\",\"locale\":\"zh\"}");
	}

	private void expectBadRequest(String body) throws Exception {
		mockMvc.perform(post("/api/assistance/journey-problem").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());
	}

	private static String validBody(String locale, String statement) {
		return """
				{"hasConnection":false,"passengerStatement":"%s","locale":"%s","currentFlight":{
				"flightNumber":"AF1668","departureDate":"2026-09-05","departureAirport":"CDG",
				"arrivalAirport":"MAN","problemAirport":"CDG","problemPhase":"DEPARTURE",
				"currentTime":"2026-09-05T07:00:00Z","currentLocalTime":"5 Sept 2026, 09:00"}}
				""".formatted(statement, locale);
	}
}
