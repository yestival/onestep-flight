package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.AirportResourcesResponse.MapStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AirportMapCandidateClassifierTest {
	private final AirportMapCandidateClassifier classifier = new AirportMapCandidateClassifier();

	@Test
	void acceptsMapEvidenceInMultipleLanguagesAndRanksTheRequestedTerminalHigher() {
		var airport = classifier.classify("https://aur-airport.test/passengers/map", "Mapa del aeropuerto",
				"Plano para pasajeros", null, "3");
		var terminal = classifier.classify("https://aur-airport.test/terminal-3/map", "航站楼地图",
				"Terminal 3 interactive map", null, "3");

		assertThat(airport.status()).isEqualTo(MapStatus.FOUND);
		assertThat(terminal.status()).isEqualTo(MapStatus.FOUND);
		assertThat(terminal.withinCategoryScore()).isGreaterThan(airport.withinCategoryScore());
	}

	@Test
	void acceptsAccentedFrenchAirportPlanTitle() {
		var result = classifier.classify(
				"https://airport-operator.test/fr/aeroports/casablanca/plans/terminal-1",
				"Plans de l'aéroport — Terminal 1", "Plans officiels", null, "1");

		assertThat(result.status()).isEqualTo(MapStatus.FOUND);
		assertThat(result.resultType()).isEqualTo(
				com.selene.onestepflight.dto.AirportResourcesResponse.MapResultType.OFFICIAL_MAP_LANDING_PAGE);
	}

	@Test
	void rejectsHomepageSitemapAndNonPassengerMapNoise() {
		assertThat(classifier.classify("https://aur-airport.test/", "Aurelia Airport",
				"Official website", null, "3").accepted()).isFalse();
		for (String[] candidate : new String[][] {
				{"https://aur-airport.test/sitemap", "Sitemap"},
				{"https://aur-airport.test/parking/map", "Parking map"},
				{"https://aur-airport.test/transport", "Bus route map"},
				{"https://aur-airport.test/community/noise-map", "Airport noise map"} }) {
			assertThat(classifier.classify(candidate[0], candidate[1], "Aurelia Airport", null, "3").accepted())
					.as(candidate[1]).isFalse();
		}
	}

	@Test
	void acceptsComprehensiveAirportMapIntentOutsideSpecializedPhrase() throws Exception {
		JsonNode fixture;
		try (InputStream stream = getClass().getResourceAsStream(
				"/fixtures/airport-resources-syd-mixed-map-title.json")) {
			assertThat(stream).isNotNull();
			fixture = new ObjectMapper().readTree(stream);
		}
		var result = classifier.classify(fixture.path("url").asText(), fixture.path("title").asText(),
				fixture.path("snippet").asText(), null, null);

		assertThat(result.accepted()).isTrue();
		assertThat(result.resultType()).isEqualTo(
				com.selene.onestepflight.dto.AirportResourcesResponse.MapResultType.OFFICIAL_MAP_LANDING_PAGE);
		assertThat(result.positiveEvidence())
				.contains("COMPREHENSIVE_MAP_INTENT_OUTSIDE_SPECIALIZED_PHRASE");
	}

	@Test
	void specializedMapCannotBorrowComprehensiveIntentFromSnippet() {
		for (String[] candidate : new String[][] {
				{"https://airport.test/parking/maps", "Airport Parking Maps"},
				{"https://airport.test/car-park/map", "Car Park Map"},
				{"https://airport.test/transport/map", "Public Transport Map"},
				{"https://airport.test/rail/map", "Rail Map"},
				{"https://airport.test/community/noise-map", "Airport Noise Map"},
				{"https://airport.test/shopping/map", "Shopping Map"},
				{"https://airport.test/development/map", "Commercial Development Map"} }) {
			var result = classifier.classify(candidate[0], candidate[1],
					"Use the official interactive airport map for terminal navigation", null, null);
			assertThat(result.rejectionType()).as(candidate[1])
					.isEqualTo("SPECIALIZED_MAP_PRIMARY_INTENT");
		}
	}

	@Test
	void rejectsNavigationGuideWithoutAnActualMapResource() {
		assertThat(classifier.classify("https://aur-airport.test/terminal/guide", "Terminal guide",
				"Passenger navigation for Terminal 3", null, "3").rejectionType())
				.isEqualTo("GUIDE_OR_GENERIC_PAGE");
		assertThat(classifier.classify("https://aur-airport.test/passengers", "Passenger services",
				"Official airport information", null, "3").accepted()).isFalse();
	}

	@Test
	void rootIsAllowedOnlyForAMapSpecificSubdomainWithBoundMapEvidence() {
		assertThat(classifier.classify("https://maps.aur-airport.test/", "Interactive airport map",
				"Wayfinding map", null, "3").status()).isEqualTo(MapStatus.FOUND);
		assertThat(classifier.classify("https://aur-airport.test/", "Airport map",
				"Interactive map", null, "3").accepted()).isFalse();
	}

	@Test
	void guideSnippetCannotPromoteTheGuideAndCategoryAlwaysBeatsTerminalBonus() {
		var guide = classifier.classify("https://aur-airport.test/terminal-3/guide", "Terminal 3 guide",
				"Use our interactive maps", "Terminal 3 guide", "3");
		var airportMap = classifier.classify("https://maps.aur-airport.test/", "Airport map",
				"Official navigation", "Airport map", "3");

		assertThat(guide.rejectionType()).isEqualTo("GUIDE_OR_GENERIC_PAGE");
		assertThat(AirportMapCandidateClassifier.compare(airportMap, guide)).isPositive();
	}

	@Test
	void acceptsFloorGuideAndDoesNotLetAuxiliaryFacilityWordsRejectAnOfficialMap() {
		var floorGuide = classifier.classify("https://tokyo-haneda.com/en/floor/index.html",
				"Floor Guide", "Find shops, transport and parking from each floor map", null, null);
		var airportMaps = classifier.classify("https://www.schiphol.nl/en/airport-maps/",
				"Schiphol Map", "Interactive map with parking, transport, shops and restaurants", null, null);

		assertThat(floorGuide.accepted()).isTrue();
		assertThat(airportMaps.accepted()).isTrue();
		assertThat(airportMaps.incidentalEvidence())
				.contains("AUXILIARY_SNIPPET_MENTIONS_NON_MAP_FACILITIES");
	}
}
