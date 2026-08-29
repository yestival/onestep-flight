package com.selene.onestepflight.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.selene.onestepflight.dto.AirportResourcesResponse.MapResultType;
import com.selene.onestepflight.dto.AirportResourcesResponse.MapStatus;

/** Determines a page's primary map intent before considering incidental snippet vocabulary. */
final class AirportMapCandidateClassifier {
	private static final Pattern SPECIALIZED_PRIMARY = Pattern.compile(
			"(?iu)(?:\\b(?:parking|car[ -]?park)[ /_-]+maps?\\b|\\bmaps?[ /_-]+(?:of |for )?(?:parking|car[ -]?park)\\b|"
			+ "\\bpublic[ -]?transport[ /_-]+maps?\\b|\\b(?:bus|train|rail|metro|subway|transit)[ /_-]+(?:route[ /_-]+)?maps?\\b|"
			+ "\\b(?:noise|shopping|commercial|development)[ /_-]+maps?\\b|\\bmaps?[ /_-]+(?:of |for )?(?:noise|shopping|commercial|development)\\b|"
			+ "\\bsitemaps?\\b|site[ /_-]+maps?|停车地图|停車地圖|交通地图|交通地圖|騒音マップ|"
			+ "주차 지도|교통 지도|карта парковки|транспортная схема|خريطة مواقف|خريطة النقل)");
	private static final Pattern ORDINARY_GUIDE = Pattern.compile(
			"(?iu)\\b(?:airport|terminal)\\s+(?:guide|information|overview|facilities|services)\\b|"
			+ "guia (?:del )?(?:aeropuerto|terminal)|guia (?:do )?(?:aeroporto|terminal)|"
			+ "guide (?:du )?(?:terminal|aeroport)|terminalfuhrer|flughafenfuhrer|"
			+ "guida (?:del )?(?:terminale|aeroporto)|机场指南|航站楼指南|航站楼介绍|"
			+ "ターミナルガイド|터미널 안내|путеводитель по терминалу|دليل مبنى الركاب");
	private static final Pattern PDF = Pattern.compile("(?iu)\\.pdf(?:$|[?#])|\\bpdf\\b");
	private static final Pattern INCIDENTAL = Pattern.compile(
			"(?iu)\\b(?:parking|car parks?|transport|bus|train|metro|shops?|restaurants?|facilities|services)\\b|"
			+ "停车|停車|交通|商店|餐厅|餐廳|设施|設施|駐車場|店舗|レストラン|주차|상점|식당");

	Classification classify(String url, String title, String snippet, String linkText, String terminal) {
		URI uri = OfficialTravelSourcePolicy.safeUri(url);
		if (uri == null) return Classification.rejected("UNSAFE_OR_NON_HTTPS_URL");
		String primary = MapIntentLexicon.normalize(String.join(" ", value(url), value(title), value(linkText)));
		String supporting = MapIntentLexicon.normalize(snippet);
		var specializedMatcher = SPECIALIZED_PRIMARY.matcher(primary);
		boolean specializedPrimaryIntent = specializedMatcher.find();
		String primaryWithoutSpecializedPhrases = specializedPrimaryIntent
				? MapIntentLexicon.normalize(specializedMatcher.replaceAll(" ")) : primary;
		boolean comprehensiveOutsideSpecializedPhrase = specializedPrimaryIntent
				&& MapIntentLexicon.hasStrongIntent(primaryWithoutSpecializedPhrases, "");
		if (specializedPrimaryIntent && !comprehensiveOutsideSpecializedPhrase) {
			return Classification.rejected("SPECIALIZED_MAP_PRIMARY_INTENT");
		}
		String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
		String firstLabel = uri.getHost().toLowerCase(Locale.ROOT).split("\\.")[0];
		boolean mapSubdomain = firstLabel.matches(".*(?:map|maps|wayfinding|navigation).*?");
		boolean homepage = path.equals("/") || path.matches("(?i)^/(?:[a-z]{2}(?:-[a-z]{2})?/)?(?:index\\.html?)?$");
		boolean strongMap = MapIntentLexicon.hasStrongIntent(primary, supporting);
		boolean floorGuide = primary.matches("(?s).*(?:\\bfloor\\s+guide\\b|フロアガイド|楼层指南|樓層指南|층별 안내).*");

		if (homepage && !mapSubdomain) return Classification.rejected("AIRPORT_HOMEPAGE");
		if (ORDINARY_GUIDE.matcher(primary).find() && !floorGuide && !strongMap) {
			return Classification.rejected("GUIDE_OR_GENERIC_PAGE");
		}
		if (!(strongMap || mapSubdomain)) return Classification.rejected("NO_PRIMARY_MAP_EVIDENCE");

		List<String> positive = new ArrayList<>(MapIntentLexicon.matchedSignals(primary, supporting));
		if (comprehensiveOutsideSpecializedPhrase) {
			positive.add("COMPREHENSIVE_MAP_INTENT_OUTSIDE_SPECIALIZED_PHRASE");
		}
		if (mapSubdomain) positive.add("OFFICIAL_MAP_SPECIFIC_SUBDOMAIN");
		List<String> incidental = new ArrayList<>();
		if (INCIDENTAL.matcher(supporting).find()) incidental.add("AUXILIARY_SNIPPET_MENTIONS_NON_MAP_FACILITIES");

		MapResultType type;
		int basePriority;
		if (PDF.matcher(primary).find()) {
			type = MapResultType.DIRECT_MAP_DOCUMENT;
			basePriority = 30;
		} else if (mapSubdomain || MapIntentLexicon.hasInteractiveIntent(primary)) {
			type = MapResultType.DIRECT_INTERACTIVE_MAP;
			basePriority = 60;
		} else {
			type = MapResultType.OFFICIAL_MAP_LANDING_PAGE;
			basePriority = MapIntentLexicon.hasAirportScope(primary) ? 50 : 40;
		}
		return new Classification(MapStatus.FOUND, type, basePriority,
				withinCategoryScore(primary, terminal), null, positive, incidental);
	}

	static int compare(Classification left, Classification right) {
		int priority = Integer.compare(left.basePriority(), right.basePriority());
		return priority != 0 ? priority : Integer.compare(left.withinCategoryScore(), right.withinCategoryScore());
	}

	private static int withinCategoryScore(String evidence, String terminal) {
		String normalizedTerminal = normalizeTerminal(terminal);
		if (normalizedTerminal.isBlank()) return 0;
		return terminalMatches(evidence, normalizedTerminal) ? 1 : 0;
	}

	private static boolean terminalMatches(String identity, String terminal) {
		return identity.contains("terminal " + terminal) || identity.contains("terminal" + terminal)
				|| identity.contains("terminal-" + terminal) || identity.contains(" t" + terminal + " ");
	}

	private static String normalizeTerminal(String terminal) {
		return MapIntentLexicon.normalize(terminal).replaceFirst("^terminal ", "").replace(" ", "");
	}

	private static String value(String value) { return value == null ? "" : value; }

	record Classification(MapStatus status, MapResultType resultType, int basePriority,
			int withinCategoryScore, String rejectionType, List<String> positiveEvidence,
			List<String> incidentalEvidence) {
		Classification {
			positiveEvidence = positiveEvidence == null ? List.of() : List.copyOf(positiveEvidence);
			incidentalEvidence = incidentalEvidence == null ? List.of() : List.copyOf(incidentalEvidence);
		}
		static Classification rejected(String reason) {
			return new Classification(MapStatus.NOT_FOUND, MapResultType.NOT_FOUND, 0, -1,
					reason, List.of(), List.of());
		}
		boolean accepted() { return status != MapStatus.NOT_FOUND; }
	}
}
