package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.AirportResourcesRequest;
import com.selene.onestepflight.dto.AirportResourcesRequest.RefreshTarget;
import com.selene.onestepflight.dto.AirportResourcesResponse;
import com.selene.onestepflight.dto.AirportResourcesResponse.MapStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SerpApiAirportResourcesArchitectureReplayTest {
	private static final List<String> CODES = List.of("AMS", "CDG", "DXB", "HND", "JFK", "LHR", "XMN");
	private static final Map<String, String> SHA256 = Map.of(
			"AMS", "0690402D1CA036B82B93AFA98D42976F9746595E9AD321548635BCF8A326D712",
			"CDG", "C3A3C5A2C7E9C424790B5C8DDB646046D608C7233CA960FFEE2A560975FEF91E",
			"DXB", "439B83466D688C5D2351F70865FAD59D8E00432C67A65E13E4EF4F672C94FED3",
			"HND", "9342B837B74ADD6B7389BC7AA80C7FFBF2DCA6344AABDB0F09DE2E5E30D5B6AF",
			"JFK", "19A46D47F403067C953C59BA457FFB56D5967D23E6A008C870EF529B06FB28ED",
			"LHR", "ED27E1534B73C387E208E84D88E44BDDB71055489DE5D1006184DAF6073FB74D",
			"XMN", "0DAD15CCAA1F909525333A7A8E1953D692E143DCD326C74CE0DB584EEC975BAF");
	private final ObjectMapper mapper = new ObjectMapper();
	private final SerpApiAirportResourcesService service = new SerpApiAirportResourcesService(mapper);

	@Test
	void frozenProductionResponsesReplayThroughTheGenericPhoneAndMapFlow() throws Exception {
		Map<String, AirportResourcesResponse> results = new LinkedHashMap<>();
		for (String code : CODES) results.put(code, parse(code));

		results.forEach((code, response) -> {
			assertThat(response.identityStatus()).as(code).isEqualTo(IdentityStatus.VERIFIED.name());
			assertThat(response.map().status()).as(code).isIn(MapStatus.FOUND, MapStatus.AIRPORT_LOCATION);
			assertThat(response.serpApiRequestCount()).as(code).isLessThanOrEqualTo(6);
			if (!"XMN".equals(code)) {
				assertThat(response.decisionTrace().candidates()).as(code)
						.anySatisfy(candidate -> assertThat(candidate.candidateId())
								.isEqualTo("IDENTITY_SEARCH_SKIPPED_STRONG_MAPS"));
			} else {
				assertThat(response.decisionTrace().candidates()).as(code)
						.noneSatisfy(candidate -> assertThat(candidate.candidateId())
								.isEqualTo("IDENTITY_SEARCH_SKIPPED_STRONG_MAPS"));
			}
		});

		assertThat(results.get("AMS").map().url()).contains("schiphol.nl/en/airport-maps");
		assertThat(results.get("HND").map().url()).contains("tokyo-haneda.com/en/floor/index.html");
		assertThat(results.get("CDG").decisionTrace().aiStages())
				.anySatisfy(stage -> assertThat(stage.stage()).isEqualTo("MAP_AI"));
		assertThat(results.get("LHR").map().url()).contains("heathrow");
		assertThat(results.get("JFK").map().url()).contains("jfkairport.com");
		assertThat(results.get("DXB").map().url()).contains("dubaiairports.ae");
		assertThat(results.get("XMN").map().status()).isIn(MapStatus.FOUND, MapStatus.AIRPORT_LOCATION);

		for (String code : List.of("AMS", "CDG", "DXB", "HND", "JFK", "XMN")) {
			assertThat(results.get(code).contact().telUri()).as(code).isNotNull();
			assertThat(results.get(code).contact().contactUrl()).as(code).isNull();
			assertThat(results.get(code).decisionTrace().aiStages()).as(code)
					.noneSatisfy(stage -> assertThat(stage.stage()).isEqualTo("CONTACT_AI"));
		}
		assertThat(results.get("LHR").contact().telUri()).isNull();
		assertThat(results.get("LHR").contact().contactUrl()).isEqualTo("https://www.heathrow.com/contact-us");
	}

	@Test
	void frozenLhrSupplementalSnippetYieldsTheRoleBoundGeneralContactNumber() throws Exception {
		JsonNode root = readFixture("LHR");
		JsonNode supplemental = null;
		for (JsonNode stage : root.path("stages")) {
			if ("CONTACT_SUPPLEMENTAL_SEARCH".equals(stage.path("stage").asText())) {
				supplemental = stage;
				break;
			}
		}
		assertThat(supplemental).isNotNull();
		JsonNode first = supplemental.path("candidates").path(0);

		assertThat(new PassengerContactPhoneExtractor().extract(
				first.path("title").asText(null), first.path("snippet").asText(null)))
				.contains("0344 335 1801");
	}

	private AirportResourcesResponse parse(String code) throws Exception {
		JsonNode root = readFixture(code);
		return service.parseSavedFixture(root,
				new AirportResourcesRequest(code, null, null, true, RefreshTarget.BOTH), Set.of());
	}

	private JsonNode readFixture(String code) throws Exception {
		String resource = "/fixtures/airport-resources-architecture-live-20260829/" + code + ".json";
		byte[] bytes;
		try (InputStream stream = getClass().getResourceAsStream(resource)) {
			assertThat(stream).as(resource).isNotNull();
			bytes = stream.readAllBytes();
		}
		assertThat(HexFormat.of().withUpperCase().formatHex(
				MessageDigest.getInstance("SHA-256").digest(bytes))).isEqualTo(SHA256.get(code));
		JsonNode root = mapper.readTree(bytes);
		assertThat(root.path("completed").asBoolean()).isTrue();
		return root;
	}
}
