package com.selene.onestepflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.selene.onestepflight.service.DemoScenarioFlightDataProvider;

@SpringBootTest(properties = "onestep.demo-recording.enabled=false")
@AutoConfigureMockMvc
class DemoRecordingDisabledApiTest {

	private static final String SESSION = "b9500530-069d-4f82-8ed9-0dbfe6b65e9f";

	@Autowired private MockMvc mockMvc;
	@Autowired private DemoScenarioFlightDataProvider demoScenarios;

	@Test
	void prodSettingMakesThePageAndEveryRecordingApiUnavailable() throws Exception {
		mockMvc.perform(get("/demo-control")).andExpect(status().isNotFound());
		mockMvc.perform(get("/demo-control.css")).andExpect(status().isNotFound());
		mockMvc.perform(get("/demo-control.js")).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/demo-recording/sessions/{id}", SESSION)).andExpect(status().isNotFound());
		mockMvc.perform(delete("/api/demo-recording/sessions/{id}/scenario-state", SESSION))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/demo-recording/integration-status")).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/demo-recording/not-a-route")).andExpect(status().isNotFound());
	}

	@Test
	void prodSettingIgnoresSessionIdsOnOrdinaryJourneyRequests() throws Exception {
		mockMvc.perform(post("/api/journeys/resolve-flight")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"flightNumber":"BA117","departureDate":"2026-08-31","demo":true,
						 "demoScenario":"GATE_CHANGE","demoSessionId":"%s","refresh":false}
						""".formatted(SESSION)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.demoSessionId").doesNotExist())
				.andExpect(jsonPath("$.simulatedDemoChange").value(false));

		@SuppressWarnings("unchecked")
		Map<String, ?> states = (Map<String, ?>) ReflectionTestUtils.getField(demoScenarios, "scenarioStates");
		assertThat(states).isNotNull();
		assertThat(states.keySet()).noneMatch(key -> key.startsWith(SESSION + ":"));
	}
}
