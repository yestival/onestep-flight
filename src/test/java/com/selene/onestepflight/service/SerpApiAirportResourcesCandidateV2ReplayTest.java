package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.selene.onestepflight.dto.AirportResourcesRequest;
import com.selene.onestepflight.dto.AirportResourcesRequest.RefreshTarget;
import com.selene.onestepflight.dto.AirportResourcesResponse;
import com.selene.onestepflight.dto.AirportResourcesResponse.MapResultType;
import com.selene.onestepflight.dto.AirportResourcesResponse.MapStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@EnabledIfSystemProperty(named = "run.output.airport.replay", matches = "true")
class SerpApiAirportResourcesCandidateV2ReplayTest {
	private static final List<String> CODES = List.of("SIN", "ICN", "HEL", "FCO", "SYD", "GRU", "IST");
	private static final Path RAW = Path.of("output", "airport-resources-next-candidate-live-run-01");
	private static final Path V1_REPLAY = Path.of("output", "airport-resources-candidate-v1-replay");
	private final ObjectMapper mapper = new ObjectMapper();
	private final SerpApiAirportResourcesService service = new SerpApiAirportResourcesService(mapper);

	@Test
	void sameCandidateRawChangesOnlySydneyMapSelection() throws Exception {
		Map<String, JsonNode> current = replayAll();
		if (Boolean.getBoolean("airport.resources.snapshot.v1")) {
			Files.createDirectories(V1_REPLAY);
			for (var entry : current.entrySet()) {
				Files.writeString(V1_REPLAY.resolve(entry.getKey() + ".json"),
						mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entry.getValue()));
			}
			return;
		}

		int rawStageCount = 0;
		int requestCount = 0;
		long officialMaps = 0;
		long googleMaps = 0;
		long notFound = 0;
		for (String code : CODES) {
			JsonNode before = mapper.readTree(Files.readString(V1_REPLAY.resolve(code + ".json")));
			JsonNode after = current.get(code);
			JsonNode raw = mapper.readTree(Files.readString(RAW.resolve(code + ".json")));
			rawStageCount += raw.path("stages").size();
			requestCount += after.path("serpApiRequestCount").asInt();
			if (after.path("map").path("status").asText().equals(MapStatus.FOUND.name())) officialMaps++;
			if (after.path("map").path("resultType").asText()
					.equals(MapResultType.GOOGLE_MAPS_AIRPORT_LOCATION.name())) googleMaps++;
			if (after.path("map").path("status").asText().equals(MapStatus.NOT_FOUND.name())) notFound++;

			assertThat(after.path("contact")).as(code + " contact").isEqualTo(before.path("contact"));
			if (!"SYD".equals(code)) {
				assertThat(productionFields(after)).as(code).isEqualTo(productionFields(before));
			} else {
				ObjectNode beforeWithoutMap = productionFields(before);
				ObjectNode afterWithoutMap = productionFields(after);
				beforeWithoutMap.remove("map");
				afterWithoutMap.remove("map");
				beforeWithoutMap.remove("rejectionReasons");
				afterWithoutMap.remove("rejectionReasons");
				assertThat(afterWithoutMap).isEqualTo(beforeWithoutMap);
				assertThat(before.path("rejectionReasons").toString())
						.contains("OFFICIAL_MAP_NOT_VERIFIED_USING_EXACT_LOCATION");
				assertThat(after.path("rejectionReasons")).isEmpty();
				assertThat(after.path("map").path("status").asText()).isEqualTo(MapStatus.FOUND.name());
				assertThat(after.path("map").path("resultType").asText())
						.isEqualTo(MapResultType.OFFICIAL_MAP_LANDING_PAGE.name());
				assertThat(after.path("map").path("url").asText())
						.isEqualTo("https://www.sydneyairport.com.au/info-sheet/maps");
				assertThat(after.path("decisionTrace").path("candidates"))
						.anySatisfy(candidate -> {
							assertThat(candidate.path("rawUrl").asText())
									.isEqualTo("https://www.sydneyairport.com.au/info-sheet/maps");
							assertThat(candidate.path("candidateSpecificReasons").toString())
									.contains("COMPREHENSIVE_MAP_INTENT_OUTSIDE_SPECIALIZED_PHRASE");
						});
			}
		}

		assertThat(officialMaps).isEqualTo(6);
		assertThat(googleMaps).isEqualTo(1);
		assertThat(notFound).isZero();
		assertThat(rawStageCount).isEqualTo(17);
		assertThat(requestCount).isEqualTo(17);
	}

	private Map<String, JsonNode> replayAll() throws Exception {
		Map<String, JsonNode> result = new LinkedHashMap<>();
		for (String code : CODES) {
			JsonNode raw = mapper.readTree(Files.readString(RAW.resolve(code + ".json")));
			AirportResourcesResponse response = service.parseSavedFixture(raw,
					new AirportResourcesRequest(code, null, null, true, RefreshTarget.BOTH), Set.of());
			result.put(code, mapper.valueToTree(response));
		}
		return result;
	}

	private static ObjectNode productionFields(JsonNode response) {
		ObjectNode result = (ObjectNode) response.deepCopy();
		result.remove("retrievedAt");
		result.remove("decisionTrace");
		return result;
	}
}
