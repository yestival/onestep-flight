package com.selene.onestepflight;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "onestep.demo-recording.enabled=true")
@AutoConfigureMockMvc
class DemoRecordingApiTest {

	private static final String SESSION = "b9500530-069d-4f82-8ed9-0dbfe6b65e9f";

	@Autowired private MockMvc mockMvc;

	@Test
	void localControlPageAndSessionApiAreAvailableOnlyThroughHiddenRoutes() throws Exception {
		mockMvc.perform(get("/demo-control"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/html"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("隔离录屏演示控制台")));
		mockMvc.perform(get("/api/demo-recording/sessions/{id}", SESSION))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.demoSessionId").value(SESSION));
		mockMvc.perform(delete("/api/demo-recording/sessions/{id}/scenario-state", SESSION))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/demo-recording/integration-status"))
				.andExpect(status().isOk());
	}

	@Test
	void malformedSessionIsNotARecordingSession() throws Exception {
		mockMvc.perform(get("/api/demo-recording/sessions/not-a-uuid"))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/demo-recording/sessions/b9500530-069d-1f82-8ed9-0dbfe6b65e9f"))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/demo-recording/sessions/b9500530-069d-4f82-7ed9-0dbfe6b65e9f"))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/demo-recording/sessions/%20b9500530-069d-4f82-8ed9-0dbfe6b65e9f%20"))
				.andExpect(status().isNotFound());
	}
}
