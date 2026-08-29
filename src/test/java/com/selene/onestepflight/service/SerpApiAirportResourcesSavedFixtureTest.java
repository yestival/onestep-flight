package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
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

class SerpApiAirportResourcesSavedFixtureTest {
	private static final List<String> CODES = List.of("LHR", "JFK", "CDG", "AMS", "XMN", "HND", "DXB");
	private final ObjectMapper mapper = new ObjectMapper();
	private final AirportIdentityCatalog catalog = new AirportIdentityCatalog(mapper);
	private final SerpApiAirportResourcesService service = new SerpApiAirportResourcesService(mapper);

	@Test
	void replaysAllSevenFrozenProductionResponsesThroughTheNewGenericPipeline() throws IOException {
		Map<String, AirportResourcesResponse> results = new LinkedHashMap<>();
		for (String code : CODES) results.put(code, parse(code));

		for (String code : List.of("LHR", "JFK", "CDG", "XMN", "HND", "DXB")) {
			AirportResourcesResponse response = results.get(code);
			assertThat(response.identityStatus()).as(code).isEqualTo(IdentityStatus.VERIFIED.name());
			assertThat(response.map().status()).as(code)
					.isIn(MapStatus.FOUND, MapStatus.AIRPORT_LOCATION);
			assertThat(response.decisionTrace().candidates()).as(code).isNotEmpty();
		}

		assertThat(results.get("JFK").contact().telUri()).isEqualTo("tel:+17182444444");
		assertThat(results.get("CDG").contact().telUri()).isEqualTo("tel:+33170363950");
		assertThat(results.get("CDG").contact().contactUrl()).isNull();
		assertThat(results.get("XMN").contact().telUri()).isEqualTo("tel:+8659296363");
		assertThat(results.get("XMN").contact().sourceUrl()).startsWith("https://www.google.com/maps/");
		assertThat(results.get("HND").contact().telUri()).isEqualTo("tel:+81357578111");
		assertThat(results.get("HND").contact().phoneConflict()).isFalse();
		assertThat(results.get("DXB").contact().telUri()).isEqualTo("tel:+97142245555");
	}

	@Test
	void amsReplayRejectsNewYorkMerchantsAndKeepsSchipholSearchAsProvisionalEvidence() throws IOException {
		AirportResourcesResponse response = parse("AMS");

		assertThat(response.identityStatus()).isEqualTo(IdentityStatus.PARTIAL.name());
		assertThat(response.contact().phoneDisplay()).isNull();
		assertThat(response.contact().contactUrl()).isNull();
		assertThat(response.decisionTrace().candidates())
				.filteredOn(candidate -> "GOOGLE_MAPS".equals(candidate.sourceType()))
				.allSatisfy(candidate -> assertThat(candidate.decision())
						.isEqualTo(AirportIdentityCandidate.Decision.REJECTED));
		assertThat(response.decisionTrace().candidates())
				.anySatisfy(candidate -> assertThat(String.valueOf(candidate.rawUrl())).contains("schiphol"));
		assertThat(response.decisionTrace().candidates())
				.filteredOn(candidate -> String.valueOf(candidate.rawUrl()).contains("dotairport.org")
						|| String.valueOf(candidate.rawUrl()).contains("omanairports"))
				.allSatisfy(candidate -> assertThat(candidate.decision())
						.isNotEqualTo(AirportIdentityCandidate.Decision.ACCEPTED));
	}

	@Test
	void legacyCdgAndHndEvidenceObeysNewShortCodeAndRegionalEquivalenceRules() {
		AirportPhoneNormalizer normalizer = new AirportPhoneNormalizer();
		AirportIdentity cdg = catalog.find("CDG").orElseThrow();
		AirportIdentity hnd = catalog.find("HND").orElseThrow();
		var shortCode = normalizer.normalizeShortCode("3950", cdg, ContactRole.GENERAL_PASSENGER,
				"https://www.parisaeroport.fr/en/passengers/contact", "FROZEN_OFFICIAL_PAGE",
				PhoneEvidenceTier.VERIFIED_PASSENGER_PAGE, true);
		var cdgLong = normalizer.normalize("+33 1 70 36 39 50", cdg, ContactRole.GENERAL_PASSENGER,
				"https://www.parisaeroport.fr/", "FROZEN_MAPS", PhoneEvidenceTier.VERIFIED_STRUCTURED_MAPS);
		var hndLocal = normalizer.normalize("03-5757-8111", hnd, ContactRole.GENERAL_PASSENGER,
				"https://tokyo-haneda.com/en/contact/", "FROZEN_OFFICIAL_PAGE",
				PhoneEvidenceTier.VERIFIED_PASSENGER_PAGE);
		var hndInternational = normalizer.normalize("+81 3-5757-8111", hnd, ContactRole.GENERAL_PASSENGER,
				"https://www.google.com/maps/", "FROZEN_MAPS", PhoneEvidenceTier.VERIFIED_STRUCTURED_MAPS);

		assertThat(shortCode.accepted()).isTrue();
		assertThat(normalizer.compare(shortCode, cdgLong)).isEqualTo(PhoneEquivalence.COMPATIBLE_DIFFERENT_SCOPE);
		assertThat(normalizer.compare(hndLocal, hndInternational)).isEqualTo(PhoneEquivalence.EQUIVALENT);
	}

	private AirportResourcesResponse parse(String code) throws IOException {
		try (InputStream stream = getClass().getResourceAsStream(
				"/fixtures/airport-resources-live-20260829/" + code + ".json")) {
			assertThat(stream).isNotNull();
			JsonNode root = mapper.readTree(stream);
			return service.parseSavedFixture(root,
					new AirportResourcesRequest(code, null, null, true, RefreshTarget.BOTH), Set.of());
		}
	}
}
