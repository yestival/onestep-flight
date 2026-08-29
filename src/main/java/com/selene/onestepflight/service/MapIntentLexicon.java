package com.selene.onestepflight.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** One multilingual map-intent source shared by search-query construction and candidate classification. */
public final class MapIntentLexicon {
	private static final List<String> ENGLISH = List.of(
			"airport map", "terminal map", "interactive map", "floor map", "floor plan", "floor guide",
			"terminal plan", "airport plan", "wayfinding", "indoor navigation");
	private static final Map<String, List<String>> COUNTRY_TERMS = countryTerms();
	private static final Pattern LITERAL_MAP_INTENT = literalPattern(allLiteralTerms());
	private static final Pattern TERMINAL_DIRECTORY = Pattern.compile("(?iu)\\bterminal\\s+director(?:y|ies)\\b");
	private static final Pattern DIRECTORY_NAVIGATION_CONTEXT = Pattern.compile(
			"(?iu)\\b(?:floor|level|location|navigate|navigation|wayfinding|map)\\b|楼层|樓層|位置|导航|導航|"
			+ "フロア|場所|案内|층|위치|안내");
	private static final Pattern INTERACTIVE = Pattern.compile(
			"(?iu)\\b(?:interactive|wayfinding|indoor navigation|zoom)\\b|交互|互动|互動|インタラクティブ|"
			+ "대화형|интерактив|تفاعلية");
	private static final Pattern FLOOR_OR_TERMINAL = Pattern.compile(
			"(?iu)\\b(?:terminal|floor|level)\\b|航站楼|航站樓|候机楼|候機樓|楼层|樓層|"
			+ "ターミナル|フロア|터미널|층별|terminaal");
	private static final Pattern AIRPORT_SCOPE = Pattern.compile(
			"(?iu)\\bairport\\s+(?:map|maps|plan|plans)\\b|机场地图|機場地圖|空港マップ|공항 지도|"
			+ "plan de l aeroport|luchthavenplattegrond|flughafenplan|mapa del aeropuerto|mapa do aeroporto|"
			+ "mappa dell aeroporto|havalimanı haritası|lentoaseman kartta|карта аэропорта|خريطة المطار");

	private MapIntentLexicon() { }

	public static List<String> queryTerms(String isoCountry) {
		List<String> local = COUNTRY_TERMS.get(normalizeCountry(isoCountry));
		if (local == null || local.isEmpty()) return List.copyOf(ENGLISH);
		LinkedHashSet<String> result = new LinkedHashSet<>(List.of(
				"airport map", "terminal map", "interactive map", "floor plan"));
		result.addAll(local);
		return List.copyOf(result);
	}

	static boolean hasStrongIntent(String mainEvidence, String supportingEvidence) {
		String main = searchable(mainEvidence);
		if (LITERAL_MAP_INTENT.matcher(main).find()) return true;
		return TERMINAL_DIRECTORY.matcher(main).find()
				&& DIRECTORY_NAVIGATION_CONTEXT.matcher(searchable(main + " " + supportingEvidence)).find();
	}

	static boolean hasInteractiveIntent(String mainEvidence) {
		return INTERACTIVE.matcher(searchable(mainEvidence)).find();
	}

	static boolean hasTerminalOrFloorIntent(String mainEvidence) {
		return FLOOR_OR_TERMINAL.matcher(searchable(mainEvidence)).find();
	}

	static boolean hasAirportScope(String mainEvidence) {
		return AIRPORT_SCOPE.matcher(searchable(mainEvidence).replace("'", " ")).find();
	}

	static List<String> matchedSignals(String mainEvidence, String supportingEvidence) {
		String main = searchable(mainEvidence);
		List<String> signals = new ArrayList<>();
		if (LITERAL_MAP_INTENT.matcher(main).find()) signals.add("PRIMARY_MAP_INTENT");
		if (TERMINAL_DIRECTORY.matcher(main).find()
				&& DIRECTORY_NAVIGATION_CONTEXT.matcher(searchable(main + " " + supportingEvidence)).find()) {
			signals.add("TERMINAL_DIRECTORY_WITH_NAVIGATION_CONTEXT");
		}
		if (hasInteractiveIntent(main)) signals.add("INTERACTIVE_OR_WAYFINDING_INTENT");
		if (hasTerminalOrFloorIntent(main)) signals.add("TERMINAL_OR_FLOOR_SCOPE");
		if (hasAirportScope(main)) signals.add("COMPREHENSIVE_AIRPORT_SCOPE");
		return List.copyOf(signals);
	}

	static String normalize(String value) {
		return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD)
				.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
				.replaceAll("[^\\p{L}\\p{N}/:?.=&'_-]+", " ").replaceAll("\\s+", " ").trim();
	}

	private static String searchable(String value) {
		return normalize(value).replaceAll("[-_/]+", " ").replaceAll("\\s+", " ").trim();
	}

	private static Map<String, List<String>> countryTerms() {
		Map<String, List<String>> values = new LinkedHashMap<>();
		values.put("CN", List.of("机场地图", "候机楼地图", "航站楼地图", "楼层图"));
		values.put("JP", List.of("空港マップ", "ターミナルマップ", "フロアガイド"));
		values.put("KR", List.of("공항 지도", "터미널 지도", "층별 안내"));
		values.put("FR", List.of("plan de l'aéroport", "plan du terminal"));
		values.put("NL", List.of("luchthavenplattegrond", "terminalplattegrond"));
		for (String country : List.of("DE", "AT", "CH")) values.put(country, List.of("Flughafenplan", "Terminalplan"));
		values.put("ES", List.of("mapa del aeropuerto", "mapa de terminal"));
		for (String country : List.of("PT", "BR")) values.put(country, List.of("mapa do aeroporto", "mapa do terminal"));
		values.put("IT", List.of("mappa dell'aeroporto", "mappa del terminal", "planimetria"));
		values.put("TR", List.of("havalimanı haritası", "terminal haritası"));
		values.put("FI", List.of("lentoaseman kartta", "terminaalikartta"));
		for (String country : List.of("AE", "SA", "QA", "BH", "OM", "KW")) {
			values.put(country, List.of("خريطة المطار", "خريطة مبنى الركاب"));
		}
		values.put("RU", List.of("карта аэропорта", "схема аэропорта", "карта терминала"));
		return Map.copyOf(values);
	}

	private static List<String> allLiteralTerms() {
		LinkedHashSet<String> values = new LinkedHashSet<>(ENGLISH);
		COUNTRY_TERMS.values().forEach(values::addAll);
		values.addAll(List.of("航站樓地圖", "候機樓地圖", "樓層圖", "平面图", "平面圖",
				"フロアマップ", "층별 지도", "plan de l'aeroport", "plans de l'aeroport",
				"mappa del terminal", "terminal plan"));
		return List.copyOf(values);
	}

	private static Pattern literalPattern(List<String> values) {
		String expression = values.stream().map(MapIntentLexicon::normalize)
				.map(Pattern::quote).reduce((left, right) -> left + "|" + right).orElse("(?!)");
		return Pattern.compile("(?:" + expression + ")", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	}

	private static String normalizeCountry(String value) {
		return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
	}
}
