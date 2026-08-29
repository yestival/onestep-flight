package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.selene.onestepflight.dto.AirportResourcesRequest;
import com.selene.onestepflight.dto.AirportResourcesRequest.RefreshTarget;
import com.selene.onestepflight.dto.AirportResourcesResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Explicit local audit gate for replaying the saved Candidate v2 eight-airport live raw. */
@EnabledIfSystemProperty(named = "run.output.airport.eight.replay", matches = "true")
class SerpApiAirportResourcesEightReplayTest {
	private static final List<String> CODES = List.of("SYD", "MUC", "NRT", "BKK", "MEX", "JNB", "YVR", "BOM");
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void replaysEachSavedRawWithoutNetworkAndWritesComparableResponses() throws Exception {
		Path input = Path.of(System.getProperty("airport.resources.replay.input",
				"output/airport-resources-candidate-v2-eight-20260829-live-run-01"));
		Path output = Path.of(System.getProperty("airport.resources.replay.output",
				"output/airport-resources-candidate-v2-eight-20260829-replay"));
		Files.createDirectories(output);
		SerpApiAirportResourcesService service = new SerpApiAirportResourcesService(mapper);
		Map<String, Object> summary = new LinkedHashMap<>();
		int stageCount = 0;
		for (String code : CODES) {
			JsonNode raw = mapper.readTree(Files.readString(input.resolve(code + ".json")));
			assertThat(raw.path("completed").asBoolean()).as(code).isTrue();
			assertThat(raw.path("request").path("airportCode").asText()).as(code).isEqualTo(code);
			assertThat(raw.path("request").path("airportName").isNull()).as(code).isTrue();
			assertThat(raw.path("request").path("terminal").isNull()).as(code).isTrue();
			assertThat(raw.path("request").path("forceRefresh").asBoolean()).as(code).isTrue();
			assertThat(raw.path("request").path("refreshTarget").asText()).as(code).isEqualTo("BOTH");
			AirportResourcesResponse response = service.parseSavedFixture(raw,
					new AirportResourcesRequest(code, null, null, true, RefreshTarget.BOTH), Set.of());
			stageCount += raw.path("stages").size();
			writeAtomically(output.resolve(code + ".json"),
					mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response));
		}
		summary.put("airports", CODES);
		summary.put("rawRequestStages", stageCount);
		summary.put("source", input.toString().replace('\\', '/'));
		writeAtomically(output.resolve("summary.json"),
				mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
	}

	private static void writeAtomically(Path target, String content) throws Exception {
		Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		try {
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
