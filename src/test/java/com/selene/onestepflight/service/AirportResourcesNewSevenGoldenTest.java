package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AirportResourcesNewSevenGoldenTest {
	private static final String RESOURCE = "/fixtures/airport-resources-new-seven-golden.json";
	private static final String SHA256 = "D84C19106DBDCFA7B213990BE4B9B7745E988878136D1B8783ADF8F3A31142AC";

	@Test
	void independentOfficialTruthIsCompleteAndHashFrozenBeforeLiveExecution() throws Exception {
		byte[] bytes;
		try (InputStream stream = getClass().getResourceAsStream(RESOURCE)) {
			assertThat(stream).isNotNull();
			bytes = stream.readAllBytes();
		}
		assertThat(HexFormat.of().withUpperCase().formatHex(
				MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(SHA256);
		JsonNode root = new ObjectMapper().readTree(bytes);
		JsonNode airports = root.path("airports");
		assertThat(airports.size()).isEqualTo(7);
		for (String code : List.of("SIN", "ICN", "HEL", "FCO", "SYD", "GRU", "IST")) {
			JsonNode airport = airports.path(code);
			assertThat(airport.isObject()).as(code).isTrue();
			assertThat(airport.path("primaryPhoneE164").asText()).as(code).startsWith("+");
			assertThat(airport.path("acceptablePhoneE164").isArray()).as(code).isTrue();
			assertThat(airport.path("contactUrls").isArray()).as(code).isTrue();
			assertThat(airport.path("sources").size()).as(code).isGreaterThanOrEqualTo(2);
			for (JsonNode source : airport.path("sources")) {
				assertThat(source.path("url").asText()).as(code).startsWith("https://");
				assertThat(source.path("evidence").asText()).as(code).isNotBlank();
			}
		}
	}
}
