package com.selene.onestepflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ShareSessionApiTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void createsReadsUpdatesAndStopsPrivacyMinimizedShare() throws Exception {
		Instant beforeCreate = Instant.now();
		JsonNode created = createSession();
		String viewToken = created.path("viewToken").asText();
		String updateToken = created.path("updateToken").asText();
		Instant expiresAt = Instant.parse(created.path("expiresAt").asText());

		assertFalse(viewToken.isBlank());
		assertFalse(updateToken.isBlank());
		assertNotEquals(viewToken, updateToken);
		assertEquals("/share/" + viewToken, created.path("viewUrl").asText());
		assertTrue(Duration.between(beforeCreate, expiresAt).toHours() >= 47);

		String publicJson = mockMvc.perform(get("/api/share-sessions/{token}", viewToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.flightNumber").value("BA117"))
				.andExpect(jsonPath("$.departureAirport").value("LHR"))
				.andExpect(jsonPath("$.arrivalAirport").value("JFK"))
				.andExpect(jsonPath("$.actionCode").value("GO_TO_GATE"))
				.andExpect(jsonPath("$.updateToken").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		assertForbiddenPrivacyFieldsAbsent(publicJson);

		mockMvc.perform(put("/api/share-sessions/{token}", viewToken)
				.header("X-Share-Update-Token", updateToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(updatedRequest()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.actionCode").value("BAGGAGE_CLAIM"))
				.andExpect(jsonPath("$.arrivalBaggageBelt").value("7"))
				.andExpect(jsonPath("$.completedSteps.length()").value(4));

		mockMvc.perform(get("/share/{token}", viewToken))
				.andExpect(status().isOk())
				.andExpect(forwardedUrl("/share.html"));

		mockMvc.perform(delete("/api/share-sessions/{token}", viewToken)
				.header("X-Share-Update-Token", updateToken))
				.andExpect(status().isNoContent());

		String stoppedJson = mockMvc.perform(get("/api/share-sessions/{token}", viewToken))
				.andExpect(status().isGone())
				.andExpect(jsonPath("$.code").value("SHARE_SESSION_STOPPED"))
				.andExpect(jsonPath("$.flightNumber").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		assertForbiddenPrivacyFieldsAbsent(stoppedJson);
	}

	@Test
	void rejectsWrongUpdateTokenWithoutChangingPublicSnapshot() throws Exception {
		JsonNode created = createSession();
		String viewToken = created.path("viewToken").asText();

		mockMvc.perform(put("/api/share-sessions/{token}", viewToken)
				.header("X-Share-Update-Token", "A".repeat(43))
				.contentType(MediaType.APPLICATION_JSON)
				.content(updatedRequest()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("SHARE_SESSION_UPDATE_FORBIDDEN"));

		mockMvc.perform(get("/api/share-sessions/{token}", viewToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.actionCode").value("GO_TO_GATE"))
				.andExpect(jsonPath("$.arrivalBaggageBelt").doesNotExist());
	}

	@Test
	void expiredShareReturnsGoneWithoutJourneyData() throws Exception {
		JsonNode created = createSession();
		String viewToken = created.path("viewToken").asText();
		jdbcTemplate.update("UPDATE share_session SET expires_at = ? WHERE view_token_hash = ?",
				Timestamp.from(Instant.now().minusSeconds(1)), sha256(viewToken));

		String response = mockMvc.perform(get("/api/share-sessions/{token}", viewToken))
				.andExpect(status().isGone())
				.andExpect(jsonPath("$.code").value("SHARE_SESSION_EXPIRED"))
				.andExpect(jsonPath("$.flightNumber").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		assertForbiddenPrivacyFieldsAbsent(response);
	}

	@Test
	void completedJourneyAcceptsNoCurrentActionAndUsesServerUpdateTime() throws Exception {
		JsonNode created = createSession();
		String viewToken = created.path("viewToken").asText();
		String updateToken = created.path("updateToken").asText();
		Instant beforeUpdate = Instant.now();

		mockMvc.perform(put("/api/share-sessions/{token}", viewToken)
				.header("X-Share-Update-Token", updateToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(completedRequest()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.completed").value(true))
				.andExpect(jsonPath("$.actionCode").doesNotExist())
				.andExpect(jsonPath("$.urgency").doesNotExist());

		String updated = mockMvc.perform(get("/api/share-sessions/{token}", viewToken))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		Instant lastUpdated = Instant.parse(objectMapper.readTree(updated).path("lastUpdated").asText());
		assertFalse(lastUpdated.isBefore(beforeUpdate));
		assertTrue(lastUpdated.isBefore(Instant.now().plusSeconds(2)));
	}

	@Test
	void rejectsIncompleteActiveSnapshotUnknownFieldsAndMalformedTokens() throws Exception {
		String incompleteActive = initialRequest()
				.replace("\"actionCode\": \"GO_TO_GATE\",", "\"actionCode\": null,")
				.replace("\"urgency\": \"NORMAL\",", "\"urgency\": null,");
		mockMvc.perform(post("/api/share-sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(incompleteActive))
				.andExpect(status().isBadRequest());

		String withForbiddenUnknownField = initialRequest().replace(
				"\"completed\": false", "\"completed\": false, \"passengerName\": \"Not accepted\"");
		mockMvc.perform(post("/api/share-sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(withForbiddenUnknownField))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/share-sessions/{token}", "short"))
				.andExpect(status().isBadRequest());

		JsonNode created = createSession();
		mockMvc.perform(delete("/api/share-sessions/{token}", created.path("viewToken").asText())
				.header("X-Share-Update-Token", "short"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void databaseAndPublicResponseContainOnlyWhitelistedJourneyFields() throws Exception {
		JsonNode created = createSession();
		String viewToken = created.path("viewToken").asText();
		String updateToken = created.path("updateToken").asText();

		Integer rawTokenMatches = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM share_session WHERE view_token_hash IN (?, ?) OR update_token_hash IN (?, ?)",
				Integer.class, viewToken, updateToken, viewToken, updateToken);
		assertEquals(0, rawTokenMatches);

		List<String> columnNames = jdbcTemplate.queryForList(
				"SELECT column_name FROM information_schema.columns WHERE table_name IN ('SHARE_SESSION', 'SHARE_SESSION_COMPLETED_STEP')",
				String.class);
		String schema = String.join(" ", columnNames).toLowerCase(Locale.ROOT);
		for (String forbidden : List.of("name", "passport", "pnr", "barcode", "file", "bbox", "api_key", "phone", "seat")) {
			assertFalse(schema.contains(forbidden), "Forbidden persistence field: " + forbidden);
		}

		String publicJson = mockMvc.perform(get("/api/share-sessions/{token}", viewToken))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertForbiddenPrivacyFieldsAbsent(publicJson);
	}

	private JsonNode createSession() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/share-sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content(initialRequest()))
				.andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private String initialRequest() {
		return """
				{
				  "flightNumber": "BA 117",
				  "departureDate": "2026-08-24",
				  "departureAirport": "lhr",
				  "arrivalAirport": "jfk",
				  "actionCode": "GO_TO_GATE",
				  "urgency": "NORMAL",
				  "destination": "B42",
				  "completedSteps": ["CHECK_IN", "BAG_DROP", "SECURITY"],
				  "terminal": "5",
				  "gate": "B42",
				  "checkInDeskOrZone": "D",
				  "dedicatedBagDropDesk": "20-24",
				  "status": "EXPECTED",
				  "lastUpdated": "2026-08-24T02:15:00Z",
				  "completed": false
				}
				""";
	}

	private String updatedRequest() {
		return """
				{
				  "flightNumber": "BA117",
				  "departureDate": "2026-08-24",
				  "departureAirport": "LHR",
				  "arrivalAirport": "JFK",
				  "actionCode": "BAGGAGE_CLAIM",
				  "urgency": "NORMAL",
				  "destination": "7",
				  "completedSteps": ["CHECK_IN", "BAG_DROP", "SECURITY", "IN_FLIGHT"],
				  "terminal": "5",
				  "arrivalBaggageBelt": "7",
				  "status": "ARRIVED",
				  "lastUpdated": "2026-08-24T10:30:00Z",
				  "completed": false
				}
				""";
	}

	private String completedRequest() {
		return """
				{
				  "flightNumber": "BA117",
				  "departureDate": "2026-08-24",
				  "departureAirport": "LHR",
				  "arrivalAirport": "JFK",
				  "actionCode": null,
				  "urgency": null,
				  "completedSteps": ["CHECK_IN", "BAG_DROP", "SECURITY", "GO_TO_GATE", "IN_FLIGHT", "BAGGAGE_CLAIM", "EXIT_AIRPORT"],
				  "lastUpdated": "2099-01-01T00:00:00Z",
				  "completed": true
				}
				""";
	}

	private void assertForbiddenPrivacyFieldsAbsent(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		for (String forbidden : List.of("passenger", "passport", "pnr", "barcode", "rawfile", "bbox", "api_key", "phone", "seat")) {
			assertFalse(lower.contains(forbidden), "Forbidden public field: " + forbidden);
		}
	}

	private String sha256(String value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
	}
}
