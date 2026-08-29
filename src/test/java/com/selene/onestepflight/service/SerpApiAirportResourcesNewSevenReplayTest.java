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

@EnabledIfSystemProperty(named = "run.output.airport.replay", matches = "true")
class SerpApiAirportResourcesNewSevenReplayTest {
	private static final List<String> CODES = List.of("SIN", "ICN", "HEL", "FCO", "SYD", "GRU", "IST");
	private static final Path BASELINE = Path.of("output",
			"airport-resources-new-seven-20260829-live-run-01");
	private final ObjectMapper mapper = new ObjectMapper();
	private final SerpApiAirportResourcesService service = new SerpApiAirportResourcesService(mapper);

	@Test
	void savedNewSevenReplayKeepsMapsAndPhonesWhileFixingNearbyCountryVariant() throws Exception {
		Map<String, AirportResourcesResponse> results = new LinkedHashMap<>();
		for (String code : CODES) {
			JsonNode raw = mapper.readTree(Files.readString(BASELINE.resolve(code + ".json")));
			results.put(code, service.parseSavedFixture(raw,
					new AirportResourcesRequest(code, null, null, true, RefreshTarget.BOTH), Set.of()));
		}

		for (String code : CODES) {
			AirportResourcesResponse response = results.get(code);
			assertThat(response.identityStatus()).as(code).isEqualTo(IdentityStatus.VERIFIED.name());
			assertThat(response.contact().telUri()).as(code).isNotNull();
			assertThat(response.contact().contactUrl()).as(code).isNull();
			assertThat(response.decisionTrace().candidates()).as(code)
					.anySatisfy(candidate -> assertThat(candidate.candidateId())
							.isEqualTo("IDENTITY_SEARCH_SKIPPED_STRONG_MAPS"));
		}

		long officialMaps = results.values().stream().filter(response ->
				response.map().status() == MapStatus.FOUND).count();
		long exactLocations = results.values().stream().filter(response ->
				response.map().resultType() == MapResultType.GOOGLE_MAPS_AIRPORT_LOCATION).count();
		assertThat(officialMaps).isEqualTo(5);
		assertThat(exactLocations).isEqualTo(2);
		assertThat(results.get("IST").contact().telUri()).isEqualTo("tel:+904441442");
		assertThat(results.get("IST").map().resultType()).isEqualTo(MapResultType.GOOGLE_MAPS_AIRPORT_LOCATION);
		assertThat(results.get("IST").decisionTrace().candidates())
				.filteredOn(candidate -> candidate.candidateId().startsWith("MAPS:"))
				.anySatisfy(candidate -> {
					assertThat(candidate.decision()).isEqualTo(AirportIdentityCandidate.Decision.ACCEPTED);
					assertThat(candidate.candidateSpecificReasons())
							.contains("CANONICAL_AIRPORT_MATCH");
				});
	}
}
