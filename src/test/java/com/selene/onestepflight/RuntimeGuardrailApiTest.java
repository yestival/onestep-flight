package com.selene.onestepflight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.selene.onestepflight.service.OperationalFlightDataProvider;

@SpringBootTest(properties = {
		"onestep.providers.variflight.enabled=false",
		"VARIFLIGHT_API_KEY=fake-test-key"
})
@AutoConfigureMockMvc
class RuntimeGuardrailApiTest {
	@Autowired private MockMvc mockMvc;
	@Autowired private List<OperationalFlightDataProvider> providers;

	@Test
	void disabledVariFlightWithFakeKeyHasNoBeanAndZeroCalls() throws Exception {
		assertFalse(providers.stream().anyMatch(provider -> "VARIFLIGHT".equals(provider.providerName())));
		mockMvc.perform(get("/api/integrations/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.variFlight").value("DISABLED"));
	}

	@Test
	void healthIsLocalOnlyAndReturnsUp() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void repeatedShareCreateFromTheSameIpRemainsAllowed() throws Exception {
		String request = """
				{
				  "flightNumber":"BA117","departureDate":"2026-08-24",
				  "departureAirport":"LHR","arrivalAirport":"JFK",
				  "actionCode":"GO_TO_GATE","urgency":"NORMAL",
				  "completedSteps":[],"completed":false
				}
				""";
		String created = mockMvc.perform(post("/api/share-sessions")
					.remoteAddress("198.51.100.20")
					.contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String viewToken = new tools.jackson.databind.ObjectMapper().readTree(created).path("viewToken").asText();

		mockMvc.perform(post("/api/share-sessions")
					.remoteAddress("198.51.100.20")
					.contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/api/share-sessions/{token}", viewToken)
					.remoteAddress("198.51.100.20"))
				.andExpect(status().isOk());
	}
}
