package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.AirportResourcesResponse.MapResultType;

class AirportMapSelectorTest {
	private final AirportMapSelector selector = new AirportMapSelector();

	@Test
	void officialInteractiveMapBeatsLandingPagePdfAndMapsFallback() {
		var selected = selector.select(List.of(
				candidate("pdf", "https://airport.example/maps/terminal.pdf", "Terminal map PDF", true, true),
				candidate("landing", "https://airport.example/airport-map", "Airport maps", true, true),
				candidate("interactive", "https://maps.airport.example/", "Interactive airport map", true, true)),
				fallback());
		assertThat(selected.selectedCandidate().classification().resultType())
				.isEqualTo(MapResultType.DIRECT_INTERACTIVE_MAP);
		assertThat(selected.validAlternatives()).hasSize(3);
		assertThat(selected.invalidCandidates()).isEmpty();
	}

	@Test
	void officialLandingPageBeatsPdfAndGoogleMapsFallback() {
		var selected = selector.select(List.of(
				candidate("pdf", "https://airport.example/maps/terminal.pdf", "Terminal map PDF", true, true),
				candidate("landing", "https://airport.example/airport-map", "Airport maps", true, true)), fallback());
		assertThat(selected.selectedCandidate().classification().resultType())
				.isEqualTo(MapResultType.OFFICIAL_MAP_LANDING_PAGE);
	}

	@Test
	void externalMapRequiresTraceableOfficialPageLink() {
		var valid = candidate("external", "https://wayfinding.example/airport/map", "Interactive airport map", false, true);
		var invalid = candidate("unbound", "https://maps-vendor.example/airport/map", "Interactive airport map", false, false);
		var selected = selector.select(List.of(invalid, valid), null);
		assertThat(selected.selectedCandidate().candidate()).isEqualTo(valid);
		assertThat(selected.invalidCandidates()).singleElement()
				.extracting(AirportMapSelector.Decision::reason)
				.isEqualTo("EXTERNAL_MAP_WITHOUT_OFFICIAL_LINK_CHAIN");
	}

	@Test
	void exploreWordAloneDoesNotCreateInteractiveMap() {
		var selected = selector.select(List.of(candidate("explore", "https://airport.example/explore",
				"Explore the airport", true, true)), fallback());
		assertThat(selected.selectedCandidate().classification().resultType())
				.isEqualTo(MapResultType.GOOGLE_MAPS_AIRPORT_LOCATION);
		assertThat(selected.invalidCandidates()).singleElement()
				.extracting(AirportMapSelector.Decision::reason).isEqualTo("NO_PRIMARY_MAP_EVIDENCE");
	}

	@Test
	void strongMapsPlaceRemainsFallbackWhenOfficialDiscoveryHasOnlyRejectedCandidates() {
		var selected = selector.select(List.of(candidate("parking", "https://airport.example/parking-map",
				"Parking map", true, true)), fallback());
		assertThat(selected.selectedCandidate().classification().resultType())
				.isEqualTo(MapResultType.GOOGLE_MAPS_AIRPORT_LOCATION);
		assertThat(selected.invalidCandidates()).hasSize(1);
	}

	@Test
	void validAlternativeIsNeverMisreportedAsRejected() {
		var selected = selector.select(List.of(
				candidate("interactive", "https://maps.airport.example/", "Interactive airport map", true, true),
				candidate("landing", "https://airport.example/airport-map", "Airport map", true, true)), null);
		assertThat(selected.validAlternatives()).singleElement()
				.extracting(decision -> decision.candidate().candidateId()).isEqualTo("landing");
		assertThat(selected.invalidCandidates()).isEmpty();
	}

	@Test
	void explicitDifferentAirportEvidenceIsRejectedBeforeRanking() {
		AirportMapCandidate wrongAirport = new AirportMapCandidate("wrong",
				"https://airport.example/maps/XYZ", "XYZ International Airport map", null,
				"Airport map", null, true, true, "TST:KTST", null, "TEST", null,
				null, null, null, null, null, null, false, List.of("IATA_CODE_CONFLICT"));
		var selected = selector.select(List.of(wrongAirport), fallback());

		assertThat(selected.selectedCandidate().classification().resultType())
				.isEqualTo(MapResultType.GOOGLE_MAPS_AIRPORT_LOCATION);
		assertThat(selected.invalidCandidates()).singleElement()
				.extracting(AirportMapSelector.Decision::reason)
				.isEqualTo("MAP_CANDIDATE_FOR_DIFFERENT_AIRPORT");
	}

	private static AirportMapCandidate candidate(String id, String url, String title,
			boolean official, boolean chain) {
		return new AirportMapCandidate(id, url, title, null, title, "https://airport.example/maps",
				official, chain, "TST:KTST", null, "TEST");
	}

	private static AirportMapCandidate fallback() {
		return new AirportMapCandidate("maps", "https://www.google.com/maps/place/?q=place_id%3Atest",
				"Test Airport", null, null, null, false, true, "TST:KTST", null, "GOOGLE_MAPS");
	}
}
