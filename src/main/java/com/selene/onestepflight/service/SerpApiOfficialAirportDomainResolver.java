package com.selene.onestepflight.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Search candidate producer. Final identity and domain authority are reconciled with Maps elsewhere. */
@Service
public class SerpApiOfficialAirportDomainResolver {
	private static final Duration VERIFIED_TTL = Duration.ofDays(7);
	private static final Duration PARTIAL_TTL = Duration.ofMinutes(5);
	private static final Duration ERROR_TTL = Duration.ofMinutes(1);
	private static final String RESOLVER_VERSION = "3";

	private final ObjectMapper objectMapper;
	private final RestClient restClient;
	private final Supplier<String> apiKeySupplier;
	private final Clock clock;
	private final Map<CacheKey, CachedResolution> cache = new ConcurrentHashMap<>();
	private final Map<CacheKey, CompletableFuture<Resolution>> inFlight = new ConcurrentHashMap<>();

	@Autowired
	public SerpApiOfficialAirportDomainResolver(ObjectMapper objectMapper) {
		this(objectMapper, buildRestClient(), () -> System.getenv("SERPAPI_API_KEY"), Clock.systemUTC(),
				new OfficialTravelSourcePolicy());
	}

	SerpApiOfficialAirportDomainResolver(ObjectMapper objectMapper, RestClient restClient,
			Supplier<String> apiKeySupplier, Clock clock, OfficialTravelSourcePolicy ignoredLegacyPolicy) {
		this.objectMapper = objectMapper;
		this.restClient = restClient;
		this.apiKeySupplier = apiKeySupplier;
		this.clock = clock;
	}

	private static RestClient buildRestClient() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(4));
		factory.setReadTimeout(Duration.ofSeconds(20));
		return RestClient.builder().baseUrl("https://serpapi.com").requestFactory(factory).build();
	}

	Resolution resolve(AirportIdentity airport, String requestedName, String datasetVersion, boolean forceRefresh) {
		CacheKey cacheKey = new CacheKey(airport.iataCode(), datasetVersion, RESOLVER_VERSION);
		Instant now = clock.instant();
		if (!forceRefresh) {
			CachedResolution cached = cache.get(cacheKey);
			if (cached != null && now.isBefore(cached.expiresAt())) return cached.resolution().withCache(true);
			if (cached != null) cache.remove(cacheKey, cached);
		}
		CompletableFuture<Resolution> own = new CompletableFuture<>();
		CompletableFuture<Resolution> shared = inFlight.putIfAbsent(cacheKey, own);
		if (shared != null) return shared.join().withCache(true);
		try {
			Resolution resolution = retrieve(airport, requestedName, forceRefresh);
			Duration ttl = switch (resolution.status()) {
				case FOUND -> VERIFIED_TTL;
				case PARTIAL, NOT_FOUND -> PARTIAL_TTL;
				case ERROR -> ERROR_TTL;
			};
			cache.put(cacheKey, new CachedResolution(resolution, now.plus(ttl)));
			own.complete(resolution);
			return resolution;
		} catch (RuntimeException exception) {
			Resolution error = Resolution.error().withRequestMade(true);
			cache.put(cacheKey, new CachedResolution(error, now.plus(ERROR_TTL)));
			own.complete(error);
			return error;
		} finally {
			inFlight.remove(cacheKey, own);
		}
	}

	/** Compatibility wrapper; production always supplies the catalog entity and dataset version. */
	Resolution resolve(String airportCode, String airportName, boolean forceRefresh) {
		AirportIdentity fallback = new AirportIdentity(airportCode, null,
				valueOr(airportName, airportCode + " Airport"), null, "ZZ", "Unknown",
				0, 0, "unknown", false, List.of());
		return resolve(fallback, airportName, "legacy", forceRefresh);
	}

	private Resolution retrieve(AirportIdentity airport, String requestedName, boolean forceRefresh) {
		String key = apiKeySupplier.get();
		if (key == null || key.isBlank()) return Resolution.error();
		Instant started = clock.instant();
		try {
			return restClient.get().uri(builder -> builder.path("/search")
					.queryParam("engine", "google")
					.queryParam("q", query(airport))
					.queryParam("hl", "en")
					.queryParam("no_cache", forceRefresh ? "true" : "false")
					.queryParam("api_key", key.trim()).build())
					.exchange((request, response) -> {
						byte[] body = response.getBody().readAllBytes();
						JsonNode root = null;
						String parseError = null;
						try {
							root = body.length == 0 ? null
									: objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
						} catch (RuntimeException exception) {
							parseError = "JSON_PARSE_ERROR:" + exception.getClass().getSimpleName();
						}
						int status = response.getStatusCode().value();
						String requestId = root == null ? null : text(root.path("search_metadata"), "id");
						if (requestId == null) requestId = response.getHeaders().getFirst("x-request-id");
						DecisionTrace.HttpStage httpStage = new DecisionTrace.HttpStage("IDENTITY_SEARCH", status,
								response.getHeaders().getContentType() == null ? null
										: response.getHeaders().getContentType().toString(),
								requestId, body.length, fingerprint(body), parseError, started, clock.instant());
						return parse(root, airport, requestedName).withRequestMade(true)
								.withHttpStages(List.of(httpStage));
					});
		} catch (ResourceAccessException exception) {
			return Resolution.error().withRequestMade(true).withHttpStages(List.of(errorStage(started, exception)));
		} catch (RuntimeException exception) {
			return Resolution.error().withRequestMade(true).withHttpStages(List.of(errorStage(started, exception)));
		}
	}

	private DecisionTrace.HttpStage errorStage(Instant started, Exception exception) {
		return new DecisionTrace.HttpStage("IDENTITY_SEARCH", null, null, null, 0, null,
				exception.getClass().getName() + ":" + safeError(exception.getMessage()), started, clock.instant());
	}

	private static String fingerprint(byte[] body) {
		try {
			return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
		} catch (Exception exception) {
			return null;
		}
	}

	private static String safeError(String value) {
		if (value == null) return "";
		return value.replaceAll("(?i)api[_-]?key[^&\\s]*", "api_key=[REDACTED]")
				.replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
	}

	static String query(AirportIdentity airport) {
		return airport.searchName() + " " + airport.iataCode() + " official airport website";
	}

	Resolution parse(JsonNode root, AirportIdentity airport, String requestedName) {
		if (root == null || !"Success".equalsIgnoreCase(root.path("search_metadata").path("status").asText())) {
			return Resolution.error();
		}
		List<AirportIdentityCandidate> candidates = new ArrayList<>();
		List<DomainEvidence> domains = new ArrayList<>();
		List<ContactPage> pages = new ArrayList<>();
		PhoneEvidence phone = null;

		JsonNode knowledge = root.path("knowledge_graph");
		if (knowledge.isObject()) {
			String title = firstNonBlank(text(knowledge, "title"), text(knowledge, "name"));
			String type = text(knowledge, "type");
			String country = firstNonBlank(text(knowledge, "country"), text(knowledge, "location"), text(knowledge, "address"));
			String url = firstCandidateUrl(knowledge.path("website"));
			String host = candidateHost(url);
			boolean airportType = AirportIdentityMatcher.normalize(type).contains("airport");
			boolean nameMatch = !intersection(AirportIdentityMatcher.significantIdentityTokens(airport),
					AirportIdentityMatcher.tokens(String.join(" ", value(title), value(country)))).isEmpty()
					|| AirportIdentityMatcher.containsToken(String.join(" ", value(title), value(country)), airport.iataCode());
			AirportIdentityMatcher.CountryTextMatch countryText =
					AirportIdentityMatcher.countryTextMatch(airport, country);
			boolean countryTextDifferent = countryText
					== AirportIdentityMatcher.CountryTextMatch.DIFFERENT_OR_UNRECOGNIZED;
			boolean verified = airportType && nameMatch && !countryTextDifferent && host != null;
			List<String> matchedSignals = verified
					? countryText == AirportIdentityMatcher.CountryTextMatch.MATCH
							? List.of("STRUCTURED_AIRPORT_ENTITY", "CANONICAL_NAME_MATCH", "COUNTRY_MATCH")
							: List.of("STRUCTURED_AIRPORT_ENTITY", "CANONICAL_NAME_MATCH")
					: List.of();
			List<String> countrySignals = countryTextDifferent
					? List.of("COUNTRY_TEXT_UNRECOGNIZED_OR_DIFFERENT") : List.of();
			AirportIdentityCandidate candidate = new AirportIdentityCandidate("SEARCH:$.knowledge_graph",
					"GOOGLE_SEARCH_KNOWLEDGE_GRAPH", "$.knowledge_graph", title, text(knowledge, "address"),
					country, null, null, url, host, null, List.of(value(type)),
					firstNonBlank(text(knowledge, "phone"), text(knowledge, "phone_number")), null,
					matchedSignals, countrySignals,
					verified ? IdentityConfidence.HIGH : IdentityConfidence.NONE,
					verified ? AirportIdentityCandidate.Decision.ACCEPTED : AirportIdentityCandidate.Decision.REJECTED,
					verified ? "VERIFIED_STRUCTURED_ENTITY"
							: countryTextDifferent
									? "INSUFFICIENT_CANONICAL_IDENTITY_SIGNALS,COUNTRY_TEXT_UNRECOGNIZED_OR_DIFFERENT"
									: "STRUCTURED_ENTITY_NOT_CANONICAL_AIRPORT");
			candidates.add(candidate);
			if (host != null) domains.add(new DomainEvidence(host, url,
					verified ? DomainAuthority.VERIFIED_STRUCTURED_ENTITY : DomainAuthority.REJECTED,
					"GOOGLE_SEARCH_KNOWLEDGE_GRAPH", airport.canonicalId(),
					verified ? candidate.matchedSignals() : List.of(candidate.reason())));
			String rawPhone = candidate.rawPhone();
			if (verified && rawPhone != null) phone = new PhoneEvidence(rawPhone, null, null, url, title);
		}

		int checked = 0;
		for (JsonNode organic : iterable(root.path("organic_results"))) {
			if (checked++ >= 10) break;
			addSearchCandidate(organic, "$.organic_results[" + (checked - 1) + "]", airport, candidates, domains, pages);
			JsonNode sitelinks = organic.path("sitelinks");
			for (String kind : List.of("inline", "expanded", "list")) {
				int index = 0;
				for (JsonNode link : iterable(sitelinks.path(kind))) {
					addSearchCandidate(link, "$.organic_results[" + (checked - 1) + "].sitelinks." + kind
							+ "[" + index++ + "]", airport, candidates, domains, pages);
				}
			}
		}
		boolean verified = domains.stream().anyMatch(DomainEvidence::verified);
		boolean provisional = domains.stream().anyMatch(item -> item.authority() == DomainAuthority.PROVISIONAL_SEARCH);
		ResolutionStatus status = verified ? ResolutionStatus.FOUND
				: provisional ? ResolutionStatus.PARTIAL : ResolutionStatus.NOT_FOUND;
		Set<String> verifiedHosts = domains.stream().filter(DomainEvidence::verified)
				.map(DomainEvidence::host).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		return new Resolution(verifiedHosts, pages, phone,
				firstNonBlank(text(knowledge, "title"), text(knowledge, "name"), airport.name()),
				firstNonBlank(text(knowledge, "country"), text(knowledge, "location"), airport.countryName()),
				status, false, false, List.copyOf(domains), List.copyOf(candidates), List.of());
	}

	Resolution parse(JsonNode root, String airportCode, String airportName) {
		AirportIdentity fallback = new AirportIdentity(airportCode, null, valueOr(airportName, airportCode + " Airport"),
				null, "ZZ", "Unknown", 0, 0, "unknown", false, List.of());
		return parse(root, fallback, airportName);
	}

	private static void addSearchCandidate(JsonNode node, String path, AirportIdentity airport,
			List<AirportIdentityCandidate> candidates, List<DomainEvidence> domains, List<ContactPage> pages) {
		String url = firstNonBlank(text(node, "link"), text(node, "url"));
		String host = candidateHost(url);
		if (host == null) return;
		String title = firstNonBlank(text(node, "title"), text(node, "name"));
		String source = firstNonBlank(text(node, "source"), text(node, "displayed_link"));
		String snippet = text(node, "snippet");
		String evidence = String.join(" ", value(title), value(source), value(snippet));
		boolean identityText = AirportIdentityMatcher.containsToken(evidence, airport.iataCode())
				|| !intersection(AirportIdentityMatcher.significantIdentityTokens(airport),
						AirportIdentityMatcher.tokens(evidence)).isEmpty();
		boolean ordinaryMerchant = AirportIdentityMatcher.tokens(evidence).stream().anyMatch(
				Set.of("restaurant", "cafe", "warehouse", "shop", "hotel", "airline")::contains);
		DomainAuthority authority = identityText && !ordinaryMerchant
				? DomainAuthority.PROVISIONAL_SEARCH : DomainAuthority.REJECTED;
		String reason = authority == DomainAuthority.PROVISIONAL_SEARCH
				? "SEARCH_TEXT_ONLY_REQUIRES_RECONCILIATION" : ordinaryMerchant
						? "ORDINARY_MERCHANT" : "NO_CANONICAL_AIRPORT_TEXT_MATCH";
		AirportIdentityCandidate candidate = new AirportIdentityCandidate("SEARCH:" + path, "GOOGLE_SEARCH", path,
				title, null, null, null, null, url, host, null, List.of(), text(node, "phone"), null,
				identityText ? List.of("CANONICAL_TEXT_MATCH") : List.of(),
				ordinaryMerchant ? List.of("ORDINARY_MERCHANT") : List.of(),
				authority == DomainAuthority.PROVISIONAL_SEARCH ? IdentityConfidence.LOW : IdentityConfidence.NONE,
				authority == DomainAuthority.PROVISIONAL_SEARCH ? AirportIdentityCandidate.Decision.VALID_NOT_SELECTED
						: AirportIdentityCandidate.Decision.REJECTED, reason);
		candidates.add(candidate);
		domains.add(new DomainEvidence(host, url, authority, "GOOGLE_SEARCH", airport.canonicalId(),
				List.of(reason)));
		pages.add(new ContactPage(url, valueOr(title, url), snippet, "GOOGLE_SEARCH", url, path));
	}

	private static Set<String> intersection(Set<String> left, Set<String> right) {
		LinkedHashSet<String> value = new LinkedHashSet<>(left);
		value.retainAll(right);
		return value;
	}

	private static String firstCandidateUrl(JsonNode node) {
		List<String> values = candidateUrls(node);
		return values.isEmpty() ? null : values.get(0);
	}

	private static List<String> candidateUrls(JsonNode node) {
		List<String> result = new ArrayList<>();
		collectCandidateUrls(node, result);
		return result;
	}

	private static void collectCandidateUrls(JsonNode node, List<String> result) {
		if (node == null || node.isMissingNode() || node.isNull()) return;
		if (node.isTextual()) {
			if (candidateHost(node.asText()) != null) result.add(node.asText().trim());
			return;
		}
		if (node.isArray()) { for (JsonNode item : node) collectCandidateUrls(item, result); return; }
		if (!node.isObject()) return;
		for (String field : List.of("link", "url", "website")) collectCandidateUrls(node.path(field), result);
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

	private static Iterable<JsonNode> iterable(JsonNode node) {
		return node != null && node.isArray() ? node : List.of();
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) if (value != null && !value.isBlank()) return value;
		return null;
	}

	private static String valueOr(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static String value(String value) { return value == null ? "" : value; }

	static boolean isContactPage(String url, String title, String snippet) {
		PageAudience audience = new PageAudienceClassifier().classify(url, title, snippet, true);
		return audience == PageAudience.PASSENGER_GENERAL || audience == PageAudience.UNKNOWN;
	}

	enum ResolutionStatus { FOUND, PARTIAL, NOT_FOUND, ERROR }

	record PhoneEvidence(String phoneDisplay, String telUri, String normalizedPhone,
			String sourceUrl, String sourceTitle) { }
	record ContactPage(String url, String title, String snippet, String sourceType,
			String sourceUrl, String sourceJsonPath) {
		ContactPage(String url, String title, String sourceType) {
			this(url, title, null, sourceType, url, null);
		}
	}

	record Resolution(Set<String> domains, List<ContactPage> contactPages, PhoneEvidence structuredPhone,
			String resolvedName, String countryOrRegion, ResolutionStatus status,
			boolean fromCache, boolean requestMade, List<DomainEvidence> domainEvidence,
			List<AirportIdentityCandidate> identityCandidates, List<DecisionTrace.HttpStage> httpStages) {
		Resolution {
			domains = domains == null ? Set.of() : Set.copyOf(domains);
			contactPages = contactPages == null ? List.of() : List.copyOf(contactPages);
			domainEvidence = domainEvidence == null ? List.of() : List.copyOf(domainEvidence);
			identityCandidates = identityCandidates == null ? List.of() : List.copyOf(identityCandidates);
			httpStages = httpStages == null ? List.of() : List.copyOf(httpStages);
		}
		Resolution(Set<String> domains, List<ContactPage> pages, PhoneEvidence phone,
				String name, String region, ResolutionStatus status, boolean cached, boolean requestMade) {
			this(domains, pages, phone, name, region, status, cached, requestMade, List.of(), List.of(), List.of());
		}
		static Resolution found(Set<String> domains) { return found(domains, List.of(), null, null, null); }
		static Resolution found(Set<String> domains, List<ContactPage> pages, PhoneEvidence phone,
				String name, String region) {
			List<DomainEvidence> evidence = domains.stream().map(host -> new DomainEvidence(host,
					"https://" + host + "/", DomainAuthority.VERIFIED_STRUCTURED_ENTITY,
					"TEST", null, List.of("TEST_VERIFIED"))).toList();
			return new Resolution(domains, pages, phone, name, region, ResolutionStatus.FOUND,
					false, false, evidence, List.of(), List.of());
		}
		static Resolution notFound() {
			return new Resolution(Set.of(), List.of(), null, null, null, ResolutionStatus.NOT_FOUND,
					false, false, List.of(), List.of(), List.of());
		}
		static Resolution error() {
			return new Resolution(Set.of(), List.of(), null, null, null, ResolutionStatus.ERROR,
					false, false, List.of(), List.of(), List.of());
		}
		Resolution withCache(boolean cached) {
			return new Resolution(domains, contactPages, structuredPhone, resolvedName, countryOrRegion,
					status, cached, false, domainEvidence, identityCandidates, httpStages);
		}
		Resolution withRequestMade(boolean made) {
			return new Resolution(domains, contactPages, structuredPhone, resolvedName, countryOrRegion,
					status, fromCache, made, domainEvidence, identityCandidates, httpStages);
		}
		Resolution withHttpStages(List<DecisionTrace.HttpStage> stages) {
			return new Resolution(domains, contactPages, structuredPhone, resolvedName, countryOrRegion,
					status, fromCache, requestMade, domainEvidence, identityCandidates, stages);
		}
	}

	private record CacheKey(String iataCode, String datasetVersion, String verifierVersion) { }
	private record CachedResolution(Resolution resolution, Instant expiresAt) { }
}
