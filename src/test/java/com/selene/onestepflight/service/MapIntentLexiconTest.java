package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class MapIntentLexiconTest {
	@Test
	void eachRequiredLocaleAddsItsOwnMapIntentToTheSharedQueryVocabulary() {
		Map<String, String> expected = Map.ofEntries(
				Map.entry("CN", "机场地图"), Map.entry("JP", "空港マップ"),
				Map.entry("KR", "공항 지도"), Map.entry("FR", "plan de l'aéroport"),
				Map.entry("NL", "luchthavenplattegrond"), Map.entry("DE", "Flughafenplan"),
				Map.entry("ES", "mapa del aeropuerto"), Map.entry("PT", "mapa do aeroporto"),
				Map.entry("IT", "mappa dell'aeroporto"), Map.entry("TR", "havalimanı haritası"),
				Map.entry("FI", "lentoaseman kartta"), Map.entry("AE", "خريطة المطار"),
				Map.entry("RU", "карта аэропорта"));

		expected.forEach((country, term) -> assertThat(MapIntentLexicon.queryTerms(country))
				.as(country).contains("airport map", term));
	}

	@Test
	void theSameVocabularyRecognizesLocalizedTitlesAndHyphenatedUrls() {
		assertThat(MapIntentLexicon.hasStrongIntent("https://airport.example/airport-maps", null)).isTrue();
		for (String title : java.util.List.of("机场地图", "空港マップ", "공항 지도",
				"Plans de l'aéroport", "Luchthavenplattegrond", "Flughafenplan",
				"Mapa del aeropuerto", "Mapa do aeroporto", "Mappa dell'aeroporto",
				"Havalimanı haritası", "Lentoaseman kartta", "خريطة المطار", "Карта аэропорта")) {
			assertThat(MapIntentLexicon.hasStrongIntent(title, null)).as(title).isTrue();
		}
	}
}
