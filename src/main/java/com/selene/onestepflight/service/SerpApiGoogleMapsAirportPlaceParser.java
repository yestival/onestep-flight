package com.selene.onestepflight.service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import tools.jackson.databind.JsonNode;

/** Retains all structured Maps candidates, then applies the canonical airport matcher once. */
final class SerpApiGoogleMapsAirportPlaceParser {
	private final OfficialTravelSourcePolicy sourcePolicy;
	private final AirportIdentityMatcher identityMatcher;
	private final AirportPhoneNormalizer phoneNormalizer;

	SerpApiGoogleMapsAirportPlaceParser(OfficialTravelSourcePolicy sourcePolicy,
			AirportPhoneSafetyValidator ignoredLegacyValidator) {
		this(sourcePolicy, new AirportIdentityMatcher(), new AirportPhoneNormalizer());
	}

	SerpApiGoogleMapsAirportPlaceParser(OfficialTravelSourcePolicy sourcePolicy,
			AirportIdentityMatcher identityMatcher, AirportPhoneNormalizer phoneNormalizer) {
		this.sourcePolicy = sourcePolicy;
		this.identityMatcher = identityMatcher;
		this.phoneNormalizer = phoneNormalizer;
	}

	Result parse(JsonNode root, AirportIdentity identity) {
		if (!success(root)) return Result.error();
		List<AirportIdentityCandidate> decisions = new ArrayList<>();
		AirportIdentityCandidate selected = null;
		for (AirportIdentityCandidate raw : candidates(root)) {
			AirportIdentityCandidate decided = identityMatcher.matchMaps(identity, raw);
			decisions.add(decided);
			if (decided.decision() == AirportIdentityCandidate.Decision.ACCEPTED
					&& (selected == null || decided.confidence().ordinal() < selected.confidence().ordinal())) {
				selected = decided;
			}
		}
		if (selected == null) return Result.notFound(decisions);
		List<AirportIdentityCandidate> finalDecisions = new ArrayList<>();
		for (AirportIdentityCandidate candidate : decisions) {
			if (candidate != selected && candidate.decision() == AirportIdentityCandidate.Decision.ACCEPTED) {
				candidate = candidate.decided(candidate.matchedSignals(), candidate.conflictingSignals(),
						candidate.confidence(), AirportIdentityCandidate.Decision.VALID_NOT_SELECTED,
						"LOWER_RANKED_CANONICAL_AIRPORT_MATCH");
			}
			finalDecisions.add(candidate);
		}

		LinkedHashSet<String> domains = new LinkedHashSet<>();
		List<DomainEvidence> domainEvidence = new ArrayList<>();
		if (selected.websiteHost() != null) {
			domains.add(selected.websiteHost());
			domainEvidence.add(new DomainEvidence(selected.websiteHost(), selected.websiteUrl(),
					DomainAuthority.VERIFIED_STRUCTURED_PLACE, "GOOGLE_MAPS", identity.canonicalId(),
					selected.matchedSignals()));
		}
		AirportPhoneCandidate phone = phoneNormalizer.normalize(selected.rawPhone(), identity,
				ContactRole.GENERAL_PASSENGER, selected.mapsUrl(), "GOOGLE_MAPS",
				PhoneEvidenceTier.VERIFIED_STRUCTURED_MAPS);
		boolean rawPhone = selected.rawPhone() != null && !selected.rawPhone().isBlank();
		return new Result(domains, selected.title(), selected.address(), selected.country(),
				phone.accepted() ? phone.passengerDisplay() : null, phone.accepted() ? phone.telUri() : null,
				phone.accepted() ? phone.e164() : null, selected.websiteUrl(), selected.mapsUrl(), true,
				false, rawPhone, phone.accepted(), selected, List.copyOf(finalDecisions),
				List.copyOf(domainEvidence), phone.accepted() ? phone : null);
	}

	/** Compatibility helper for older focused parser tests; production uses the canonical-identity overload. */
	Result parse(JsonNode root, String airportCode, String airportName, Set<String> searchDomains) {
		if (!success(root)) return Result.error();
		List<AirportIdentityCandidate> raw = candidates(root);
		for (AirportIdentityCandidate candidate : raw) {
			boolean airportType = candidate.placeTypes().stream()
					.map(AirportIdentityMatcher::normalize).anyMatch(value -> value.contains("airport"));
			boolean name = OfficialTravelSourcePolicy.evidenceMatchesAirport(
					String.join(" ", value(candidate.title()), value(candidate.address())), airportCode, airportName);
			boolean cross = candidate.websiteHost() != null && searchDomains != null
					&& searchDomains.stream().anyMatch(domain -> hostMatches(candidate.websiteHost(), domain));
			if (!airportType || !(name || cross)) continue;
			LinkedHashSet<String> domains = new LinkedHashSet<>();
			if (candidate.websiteHost() != null) domains.add(candidate.websiteHost());
			AirportIdentityCandidate accepted = candidate.decided(List.of("LEGACY_TEST_MATCH"), List.of(),
					IdentityConfidence.MEDIUM, AirportIdentityCandidate.Decision.ACCEPTED, "LEGACY_TEST_MATCH");
			return new Result(domains, candidate.title(), candidate.address(), candidate.country(), null, null,
					null, candidate.websiteUrl(), candidate.mapsUrl(), true, false,
					candidate.rawPhone() != null, false, accepted, List.of(accepted), List.of(), null);
		}
		return Result.notFound(raw);
	}

	List<AirportIdentityCandidate> candidates(JsonNode root) {
		List<AirportIdentityCandidate> result = new ArrayList<>();
		addCandidate(root.path("place_results"), "$.place_results", result);
		addArray(root.path("local_results"), "$.local_results", result);
		addArray(root.path("local_results").path("places"), "$.local_results.places", result);
		return List.copyOf(result);
	}

	private static void addArray(JsonNode node, String path, List<AirportIdentityCandidate> result) {
		if (!node.isArray()) return;
		int index = 0;
		for (JsonNode item : node) addCandidate(item, path + "[" + index++ + "]", result);
	}

	private static void addCandidate(JsonNode node, String path, List<AirportIdentityCandidate> result) {
		if (!node.isObject()) return;
		String title = firstNonBlank(text(node, "title"), text(node, "name"));
		String address = firstNonBlank(text(node, "address"), text(node, "full_address"));
		String country = firstNonBlank(text(node, "country"), text(node, "country_name"), text(node, "region"));
		String website = firstNonBlank(text(node, "website"), text(node, "website_url"));
		String host = candidateHost(website);
		String placeId = firstNonBlank(text(node, "place_id"), text(node, "google_place_id"));
		Double latitude = coordinate(node, "latitude");
		Double longitude = coordinate(node, "longitude");
		String mapsUrl = strictGoogleMapsLocation(firstNonBlank(text(node, "link"), text(node, "place_url"),
				text(node, "maps_url")));
		if (mapsUrl == null) mapsUrl = structuredGoogleMapsLocation(placeId, latitude, longitude, title);
		result.add(new AirportIdentityCandidate("MAPS:" + path, "GOOGLE_MAPS", path, title, address,
				country, latitude, longitude, website, host, placeId, placeTypes(node),
				firstNonBlank(text(node, "phone"), text(node, "phone_number"),
						text(node, "international_phone_number")), mapsUrl,
				List.of(), List.of(), IdentityConfidence.NONE, AirportIdentityCandidate.Decision.PENDING, null));
	}

	private static List<String> placeTypes(JsonNode node) {
		List<String> result = new ArrayList<>();
		for (String field : List.of("type", "type_id")) {
			JsonNode raw = node.path(field);
			if (raw.isArray()) {
				for (JsonNode value : raw) if (value.isTextual()) result.add(value.asText().replace('_', ' '));
			} else {
				String value = text(node, field);
				if (value != null) result.add(value.replace('_', ' '));
			}
		}
		for (String field : List.of("types", "type_ids")) {
			JsonNode values = node.path(field);
			if (values.isArray()) for (JsonNode value : values) if (value.isTextual()) result.add(value.asText().replace('_', ' '));
		}
		return List.copyOf(result);
	}

	private static Double coordinate(JsonNode node, String field) {
		for (String parent : List.of("gps_coordinates", "coordinates")) {
			JsonNode value = node.path(parent).path(field);
			if (value.isNumber()) return value.asDouble();
		}
		JsonNode direct = node.path(field);
		return direct.isNumber() ? direct.asDouble() : null;
	}

	private static boolean success(JsonNode root) {
		return root != null && "Success".equalsIgnoreCase(root.path("search_metadata").path("status").asText());
	}

	private static String candidateHost(String value) {
		if (value == null || value.isBlank()) return null;
		try {
			URI uri = URI.create(value.trim()).normalize();
			if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
					|| uri.getHost() == null || uri.getRawUserInfo() != null) return null;
			String host = uri.getHost().toLowerCase(Locale.ROOT);
			return host.startsWith("www.") ? host.substring(4) : host;
		} catch (IllegalArgumentException exception) { return null; }
	}

	private static String strictGoogleMapsLocation(String value) {
		if (value == null || value.isBlank()) return null;
		try {
			URI uri = URI.create(value.trim()).normalize();
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) return null;
			String host = uri.getHost().toLowerCase(Locale.ROOT);
			String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
			if (!(host.equals("google.com") || host.equals("www.google.com")) || !path.startsWith("/maps/")) return null;
			if (path.equals("/maps/search") || path.startsWith("/maps/search/")) return null;
			return uri.toString();
		} catch (IllegalArgumentException exception) { return null; }
	}

	private static String structuredGoogleMapsLocation(String placeId, Double latitude, Double longitude, String title) {
		if (placeId != null && placeId.matches("[A-Za-z0-9_-]{8,200}")) {
			return "https://www.google.com/maps/place/?q=place_id%3A" + placeId;
		}
		if (latitude == null || longitude == null || !Double.isFinite(latitude) || !Double.isFinite(longitude)
				|| latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return null;
		String label = URLEncoder.encode(value(title), StandardCharsets.UTF_8).replace("+", "%20");
		return String.format(Locale.ROOT, "https://www.google.com/maps/place/%s/@%.6f,%.6f,15z",
				label, latitude, longitude);
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) if (value != null && !value.isBlank()) return value;
		return null;
	}

	private static boolean hostMatches(String host, String domain) {
		String normalized = domain == null ? "" : domain.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
		return host.equals(normalized) || host.endsWith("." + normalized);
	}

	private static String value(String value) { return value == null ? "" : value; }

	record Result(Set<String> domains, String localName, String address, String countryOrRegion,
			String phoneDisplay, String telUri, String normalizedPhone, String websiteUrl,
			String mapsLocationUrl, boolean matchedAirport, boolean upstreamError,
			boolean structuredPhonePresent, boolean acceptedStructuredPhonePresent,
			AirportIdentityCandidate selectedPlace, List<AirportIdentityCandidate> candidates,
			List<DomainEvidence> domainEvidence, AirportPhoneCandidate phoneCandidate) {
		Result {
			domains = domains == null ? Set.of() : Set.copyOf(domains);
			candidates = candidates == null ? List.of() : List.copyOf(candidates);
			domainEvidence = domainEvidence == null ? List.of() : List.copyOf(domainEvidence);
		}
		Result(Set<String> domains, String localName, String address, String countryOrRegion,
				String phoneDisplay, String telUri, String normalizedPhone, String websiteUrl,
				String mapsLocationUrl, boolean matchedAirport, boolean upstreamError, boolean structuredPhonePresent) {
			this(domains, localName, address, countryOrRegion, phoneDisplay, telUri, normalizedPhone,
					websiteUrl, mapsLocationUrl, matchedAirport, upstreamError, structuredPhonePresent,
					phoneDisplay != null, null, List.of(), List.of(), null);
		}
		static Result notFound() { return notFound(List.of()); }
		static Result notFound(List<AirportIdentityCandidate> candidates) {
			return new Result(Set.of(), null, null, null, null, null, null, null, null,
					false, false, candidates.stream().anyMatch(candidate -> candidate.rawPhone() != null),
					false, null, candidates, List.of(), null);
		}
		static Result error() {
			return new Result(Set.of(), null, null, null, null, null, null, null, null,
					false, true, false, false, null, List.of(), List.of(), null);
		}
	}
}
