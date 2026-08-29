package com.selene.onestepflight.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deterministic canonical-entity matcher shared by Search and Google Maps reconciliation. */
public final class AirportIdentityMatcher {
	public static final String VERIFIER_VERSION = "3";
	private static final Set<String> GENERIC = Set.of("airport", "international", "terminal", "aeroport",
			"aeropuerto", "aeroporto", "flughafen", "aerodrome", "airfield", "regional", "municipal",
			"the", "of", "and", "city", "passenger", "hub");
	private static final Set<String> MERCHANT_TYPES = Set.of("restaurant", "shop", "store", "office",
			"warehouse", "cafe", "coffee", "hotel", "parking", "airline", "lounge", "travel agency");

	public AirportIdentityCandidate matchMaps(AirportIdentity identity, AirportIdentityCandidate raw) {
		List<String> matches = new ArrayList<>();
		List<String> conflicts = new ArrayList<>();
		Set<String> types = tokens(String.join(" ", raw.placeTypes()));
		boolean airportType = types.contains("airport") || types.contains("aerodrome")
				|| raw.placeTypes().stream().map(AirportIdentityMatcher::normalize)
						.anyMatch(value -> value.contains("international airport"));
		if (!airportType) conflicts.add("PLACE_TYPE_NOT_AIRPORT"); else matches.add("PLACE_TYPE_AIRPORT");
		if (types.stream().anyMatch(MERCHANT_TYPES::contains)) conflicts.add("ORDINARY_MERCHANT_TYPE");

		CountryTextMatch countryText = countryTextMatch(identity, raw.country(), raw.address());
		if (countryText == CountryTextMatch.MATCH) matches.add("COUNTRY_MATCH");
		else if (countryText == CountryTextMatch.DIFFERENT_OR_UNRECOGNIZED) {
			conflicts.add("COUNTRY_TEXT_UNRECOGNIZED_OR_DIFFERENT");
		}

		Set<String> identityTokens = significantIdentityTokens(identity);
		Set<String> candidateTokens = tokens(String.join(" ", value(raw.title()), value(raw.address())));
		Set<String> shared = new LinkedHashSet<>(identityTokens);
		shared.retainAll(candidateTokens);
		boolean independentIata = containsToken(String.join(" ", value(raw.title()), value(raw.address())), identity.iataCode());
		if (independentIata) matches.add("INDEPENDENT_IATA");
		if (!shared.isEmpty()) matches.add("NAME_TOKEN:" + String.join(",", shared));

		boolean accepted;
		IdentityConfidence confidence;
		if (raw.latitude() != null && raw.longitude() != null) {
			double distance = distanceKm(identity.latitude(), identity.longitude(), raw.latitude(), raw.longitude());
			if (distance <= 15) matches.add(String.format(Locale.ROOT, "COORDINATES_WITHIN_15KM:%.2f", distance));
			else conflicts.add(String.format(Locale.ROOT, "COORDINATES_TOO_FAR:%.2f", distance));
			accepted = airportType && !containsHardConflict(conflicts) && distance <= 15
					&& (independentIata || shared.stream().anyMatch(token -> token.length() >= 4));
			confidence = accepted ? IdentityConfidence.VERY_HIGH : IdentityConfidence.NONE;
		} else {
			boolean municipality = containsPhrase(String.join(" ", value(raw.address()), value(raw.country())), identity.municipality());
			if (municipality) matches.add("MUNICIPALITY_MATCH");
			boolean countrySupportsNoCoordinateMatch = countryText != CountryTextMatch.DIFFERENT_OR_UNRECOGNIZED;
			accepted = airportType && !containsHardConflict(conflicts)
					&& (independentIata && municipality && countrySupportsNoCoordinateMatch
							|| shared.size() >= 2 && municipality && countrySupportsNoCoordinateMatch);
			confidence = accepted ? IdentityConfidence.HIGH : IdentityConfidence.NONE;
		}
		String reason;
		if (accepted) reason = "CANONICAL_AIRPORT_MATCH";
		else if (containsHardConflict(conflicts)) reason = String.join(",", conflicts);
		else {
			List<String> reasons = new ArrayList<>();
			reasons.add("INSUFFICIENT_CANONICAL_IDENTITY_SIGNALS");
			reasons.addAll(conflicts);
			reason = String.join(",", reasons);
		}
		return raw.decided(matches, conflicts, confidence,
				accepted ? AirportIdentityCandidate.Decision.ACCEPTED : AirportIdentityCandidate.Decision.REJECTED,
				reason);
	}

	public boolean requestNameMatches(AirportIdentity identity, String requestName) {
		if (requestName == null || requestName.isBlank()) return true;
		Set<String> expected = significantIdentityTokens(identity);
		Set<String> supplied = tokens(requestName);
		expected.retainAll(supplied);
		return !expected.isEmpty() || containsToken(requestName, identity.iataCode());
	}

	public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
		double earthRadius = 6371.0088;
		double first = Math.toRadians(lat1);
		double second = Math.toRadians(lat2);
		double deltaLatitude = Math.toRadians(lat2 - lat1);
		double deltaLongitude = Math.toRadians(lon2 - lon1);
		double a = Math.sin(deltaLatitude / 2) * Math.sin(deltaLatitude / 2)
				+ Math.cos(first) * Math.cos(second)
				* Math.sin(deltaLongitude / 2) * Math.sin(deltaLongitude / 2);
		return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
	}

	static Set<String> significantIdentityTokens(AirportIdentity identity) {
		LinkedHashSet<String> values = new LinkedHashSet<>();
		values.addAll(tokens(identity.name()));
		for (String alias : identity.aliases()) values.addAll(tokens(alias));
		values.removeIf(value -> value.length() < 4 || GENERIC.contains(value));
		return values;
	}

	static Set<String> tokens(String value) {
		LinkedHashSet<String> values = new LinkedHashSet<>(Arrays.asList(normalize(value).split(" ")));
		values.removeIf(String::isBlank);
		return values;
	}

	static String normalize(String value) {
		if (value == null) return "";
		return Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
				.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").replaceAll("\\s+", " ").trim();
	}

	static boolean containsToken(String value, String token) {
		String normalized = normalize(value);
		String expected = normalize(token);
		return !expected.isBlank() && (" " + normalized + " ").contains(" " + expected + " ");
	}

	static CountryTextMatch countryTextMatch(AirportIdentity identity, String... rawValues) {
		String joined = String.join(" ", Arrays.stream(rawValues).map(AirportIdentityMatcher::value).toList());
		if (normalize(joined).isBlank()) return CountryTextMatch.ABSENT;
		if (containsPhrase(joined, identity.countryName()) || containsToken(joined, identity.isoCountry())) {
			return CountryTextMatch.MATCH;
		}
		String iso = identity.isoCountry() == null ? "" : identity.isoCountry().trim().toUpperCase(Locale.ROOT);
		if (iso.matches("[A-Z]{2}")) {
			String standardEnglishName = new Locale.Builder().setRegion(iso).build().getDisplayCountry(Locale.ENGLISH);
			if (containsPhrase(joined, standardEnglishName)) return CountryTextMatch.MATCH;
		}
		return CountryTextMatch.DIFFERENT_OR_UNRECOGNIZED;
	}

	private static boolean containsPhrase(String value, String expected) {
		return expected != null && !expected.isBlank() && normalize(value).contains(normalize(expected));
	}

	private static boolean containsHardConflict(List<String> conflicts) {
		return conflicts.stream().anyMatch(value -> value.startsWith("PLACE_TYPE_")
				|| value.equals("ORDINARY_MERCHANT_TYPE") || value.startsWith("COORDINATES_TOO_FAR"));
	}

	enum CountryTextMatch { MATCH, ABSENT, DIFFERENT_OR_UNRECOGNIZED }

	private static String value(String value) { return value == null ? "" : value; }
}
