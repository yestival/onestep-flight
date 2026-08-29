package com.selene.onestepflight.service;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Applies generic URL safety rules to dynamically verified airport/operator domains. */
public final class OfficialTravelSourcePolicy {
	private static final Pattern IP_V4 = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}");
	private static final Pattern IP_V6 = Pattern.compile(".*:.*");
	private static final Pattern AIRLINE_IDENTITY = Pattern.compile(
			"(?:\\b(?:airlines?|airways|air lines|aerol[ií]nea|companhia a[eé]rea|compagnie a[eé]rienne|fluggesellschaft|авиакомпания)\\b|"
			+ "航空公司|航空会社|항공사)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern OPERATOR_SIGNAL = Pattern.compile(
			"(?:airport authority|port authority|airport operator|operated by|operator of|manages |official website|official site|"
			+ "autoridad aeroportuaria|operador(?:a)? del aeropuerto|sitio (?:web )?oficial|"
			+ "autoridade aeroportuaria|operador(?:a)? do aeroporto|site oficial|"
			+ "exploitant de l aeroport|site officiel|flughafenbetreiber|offizielle website|"
			+ "gestore aeroportuale|sito ufficiale|机场运营|机场管理|官方网站|空港運営|公式サイト|공항 운영|공식 사이트|"
			+ "оператор аэропорта|официальный сайт|مشغل المطار|الموقع الرسمي)",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern CONTACT_RESOURCE_SIGNAL = Pattern.compile(
			"contact|customer[ -]?service|passenger enquir|help|联系|聯絡|お問い合わせ|문의|ติดต่อ|اتصل|تواصل|контакт|contacto|contato|kontakt",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern BLOCKED_HOST_CATEGORY = Pattern.compile(
			"(?:^|[.-])(?:airlines?|airways|travel|trips?|tourism|hotels?|forum|directory|phonebook)(?:[.-]|$)",
			Pattern.CASE_INSENSITIVE);
	private static final Set<String> BLOCKED_EXACT = Set.of(
			"google.com", "googleusercontent.com", "googleapis.com", "serpapi.com", "bing.com",
			"facebook.com", "instagram.com", "twitter.com", "x.com", "youtube.com", "tiktok.com",
			"tripadvisor.com", "yelp.com", "reddit.com", "flyertalk.com", "quora.com", "medium.com",
			"wikipedia.org", "wikivoyage.org", "airportia.com", "flightaware.com", "flightradar24.com",
			"airports-worldwide.com", "world-airport-codes.com", "airport-information.com",
			"sleepinginairports.net", "airlinequality.com", "skyscanner.com", "kayak.com", "expedia.com");
	private static final Set<String> GENERIC_NAME_WORDS = Set.of(
			"airport", "international", "regional", "municipal", "terminal", "the", "of", "and",
			"aeropuerto", "aeroport", "airfield", "city", "metropolitan");

	Optional<ValidatedSource> validateAirport(String url, String title, String source, String supportingText,
			String airportCode, String airportName, Set<String> verifiedDomains) {
		URI uri = safeUri(url);
		String verifiedDomain = matchingVerifiedDomain(uri, verifiedDomains);
		if (verifiedDomain == null || isAirlineAttribution(title, source)) return Optional.empty();
		// A verified operator domain may serve several airports. A local-language contact resource
		// need not repeat the English name, but an unrelated page still cannot cross-bind identity.
		if (!domainIdentifiesAirport(verifiedDomain, airportCode, airportName)) {
			String evidence = String.join(" ", valueOrEmpty(uri), valueOrEmpty(title),
					valueOrEmpty(source), valueOrEmpty(supportingText));
			if (!evidenceMatchesAirport(evidence, airportCode, airportName)
					&& !CONTACT_RESOURCE_SIGNAL.matcher(evidence).find()) return Optional.empty();
		}
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		return Optional.of(new ValidatedSource(normalizeUrl(uri), safeTitle(title, source, host), host));
	}

	Optional<ValidatedSource> validateCitedAirport(String url, String title, String source, String supportingText,
			String airportCode, String airportName, Set<String> verifiedDomains) {
		Optional<ValidatedSource> verified = validateAirport(url, title, source, supportingText,
				airportCode, airportName, verifiedDomains);
		if (verified.isPresent()) return verified;
		if (!acceptsOrganicCandidate(url, title, source, supportingText, airportCode, airportName)) {
			return Optional.empty();
		}
		URI uri = safeUri(url);
		if (uri == null) return Optional.empty();
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		return Optional.of(new ValidatedSource(normalizeUrl(uri), safeTitle(title, source, host), host));
	}

	Optional<URI> validateRelatedAirportUrl(String url, Set<String> verifiedDomains) {
		URI uri = safeUri(url);
		if (uri == null) uri = upgradeVerifiedHttp(url, verifiedDomains);
		return matchingVerifiedDomain(uri, verifiedDomains) == null
				? Optional.empty() : Optional.of(normalizeUrl(uri));
	}

	private static URI upgradeVerifiedHttp(String value, Set<String> verifiedDomains) {
		if (value == null || value.isBlank()) return null;
		try {
			URI raw = URI.create(value.trim()).normalize();
			if (!"http".equalsIgnoreCase(raw.getScheme()) || raw.getHost() == null
					|| raw.getRawUserInfo() != null || raw.getPort() != -1 && raw.getPort() != 80
					|| matchingVerifiedDomain(raw, verifiedDomains) == null) return null;
			URI upgraded = new URI("https", null, raw.getHost(), -1,
					raw.getPath(), raw.getQuery(), null);
			return safeUri(upgraded.toString());
		} catch (IllegalArgumentException | URISyntaxException exception) {
			return null;
		}
	}

	boolean acceptsKnowledgeGraphCandidate(String url, String entityEvidence,
			String airportCode, String airportName) {
		URI uri = safeUri(url);
		return uri != null && evidenceMatchesAirport(entityEvidence, airportCode, airportName);
	}

	boolean acceptsOrganicCandidate(String url, String title, String source, String snippet,
			String airportCode, String airportName) {
		URI uri = safeUri(url);
		if (uri == null || isAirlineAttribution(title, source)) return false;
		String evidence = String.join(" ", valueOrEmpty(title), valueOrEmpty(source), valueOrEmpty(snippet));
		return evidenceMatchesAirport(evidence, airportCode, airportName)
				&& OPERATOR_SIGNAL.matcher(normalizeWords(evidence)).find();
	}

	String normalizeVerifiedDomain(String url) {
		URI uri = safeUri(url);
		return uri == null ? null : normalizeVerifiedDomain(uri);
	}

	private static String normalizeVerifiedDomain(URI uri) {
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		return host.startsWith("www.") ? host.substring(4) : host;
	}

	static URI safeUri(String value) {
		if (value == null || value.isBlank()) return null;
		try {
			URI uri = URI.create(value.trim()).normalize();
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
					|| uri.getPort() != -1 && uri.getPort() != 443 || uri.getHost() == null) return null;
			String host = uri.getHost().toLowerCase(Locale.ROOT);
			if (!host.contains(".") || host.contains("xn--") || IP_V4.matcher(host).matches()
					|| IP_V6.matcher(host).matches() || !IDN.toASCII(host).equals(host) || blocked(host)) return null;
			return uri;
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	static boolean evidenceMatchesAirport(String evidence, String airportCode, String airportName) {
		String normalized = normalizeWords(evidence);
		for (String token : significantAirportWords(airportName)) {
			if (containsWord(normalized, token)) return true;
		}
		return false;
	}

	static boolean domainIdentifiesAirport(String domain, String airportCode, String airportName) {
		String normalized = normalizeWords(domain == null ? "" : domain.replace('.', ' ').replace('-', ' '));
		String code = normalizeWords(airportCode);
		if (!code.isBlank() && containsWord(normalized, code)) return true;
		for (String token : significantAirportWords(airportName)) {
			if (containsWord(normalized, token)) return true;
		}
		return false;
	}

	private static Set<String> significantAirportWords(String airportName) {
		LinkedHashSet<String> result = new LinkedHashSet<>();
		for (String token : normalizeWords(airportName).split(" ")) {
			if (token.length() >= 4 && !GENERIC_NAME_WORDS.contains(token)) result.add(token);
		}
		return result;
	}

	static String matchingVerifiedDomain(URI uri, Set<String> verifiedDomains) {
		if (uri == null || verifiedDomains == null || verifiedDomains.isEmpty()) return null;
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		for (String rawDomain : verifiedDomains) {
			if (rawDomain == null) continue;
			String domain = rawDomain.toLowerCase(Locale.ROOT);
			if (domain.startsWith("www.")) domain = domain.substring(4);
			if (domainMatches(host, domain)) return domain;
		}
		return null;
	}

	private static boolean isAirlineAttribution(String title, String source) {
		return AIRLINE_IDENTITY.matcher(String.join(" ", valueOrEmpty(title), valueOrEmpty(source))).find();
	}

	private static boolean blocked(String host) {
		for (String domain : BLOCKED_EXACT) if (domainMatches(host, domain)) return true;
		return BLOCKED_HOST_CATEGORY.matcher(host).find();
	}

	private static boolean containsWord(String normalized, String word) {
		return (" " + normalized + " ").contains(" " + word + " ");
	}

	static String normalizeWords(String value) {
		if (value == null) return "";
		return Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
				.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
	}

	private static URI normalizeUrl(URI uri) {
		try {
			String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
			return new URI("https", null, uri.getHost().toLowerCase(Locale.ROOT), -1,
					path, uri.getQuery(), null).normalize();
		} catch (URISyntaxException exception) {
			throw new IllegalArgumentException("Unable to normalize validated official URL", exception);
		}
	}

	private static String safeTitle(String title, String source, String host) {
		String value = firstNonBlank(title, source, host).replaceAll("[\\p{Cc}\\p{Cf}]", "")
				.replaceAll("\\s+", " ").trim();
		return value.length() > 160 ? value.substring(0, 160) : value;
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) if (value != null && !value.isBlank()) return value;
		return "Official airport website";
	}

	private static String valueOrEmpty(Object value) {
		return value == null ? "" : value.toString();
	}

	private static boolean domainMatches(String host, String domain) {
		return domain != null && !domain.isBlank() && (host.equals(domain) || host.endsWith("." + domain));
	}

	record ValidatedSource(URI uri, String title, String host) { }
}
