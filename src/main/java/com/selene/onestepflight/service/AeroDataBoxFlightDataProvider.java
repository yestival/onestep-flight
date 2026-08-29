package com.selene.onestepflight.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.selene.onestepflight.dto.ProviderFlightResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class AeroDataBoxFlightDataProvider implements OperationalFlightDataProvider {

	private static final Duration CACHE_TTL = Duration.ofMinutes(5);
	private static final String HOST = "aerodatabox.p.rapidapi.com";
	private static final Logger LOGGER = LoggerFactory.getLogger(AeroDataBoxFlightDataProvider.class);
	private final Supplier<String> apiKeySupplier;
	private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();
	private final ObjectMapper objectMapper;
	private final RestClient restClient;

	@Autowired
	public AeroDataBoxFlightDataProvider(ObjectMapper objectMapper) {
		this(objectMapper, buildRestClient(), () -> System.getenv("AERODATABOX_RAPIDAPI_KEY"));
	}

	AeroDataBoxFlightDataProvider(ObjectMapper objectMapper, RestClient restClient,
			Supplier<String> apiKeySupplier) {
		this.objectMapper = objectMapper;
		this.restClient = restClient;
		this.apiKeySupplier = apiKeySupplier;
	}

	private static RestClient buildRestClient() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(3));
		requestFactory.setReadTimeout(Duration.ofSeconds(5));
		return RestClient.builder()
				.baseUrl("https://" + HOST)
				.requestFactory(requestFactory)
				.build();
	}

	@Override
	public String providerName() {
		return "AERODATABOX";
	}

	@Override
	public ProviderFlightResult findFlight(String flightNumber, LocalDate departureDate,
			String departureAirportHint, String arrivalAirportHint, boolean refresh) {
		return findFlight(flightNumber, departureDate, departureAirportHint, arrivalAirportHint, null, refresh);
	}

	@Override
	public ProviderFlightResult findFlight(String flightNumber, LocalDate departureDate,
			String departureAirportHint, String arrivalAirportHint,
			String scheduledDepartureHint, boolean refresh) {
		Instant attemptedAt = Instant.now();
		String correlationId = UUID.randomUUID().toString();
		String key = apiKeySupplier.get();
		if (key == null || key.isBlank()) {
			return ProviderFlightResult.failure(providerName(), "NOT_CONFIGURED", "AERODATABOX_NOT_CONFIGURED", attemptedAt);
		}
		key = key.trim();
		if (departureDate == null) {
			return ProviderFlightResult.failure(providerName(), "NOT_FOUND", "AERODATABOX_DATE_REQUIRED", attemptedAt);
		}
		String normalizedFlightNumber = normalizeFlight(flightNumber);
		String normalizedDepartureHint = normalizeAirportHint(departureAirportHint);
		String normalizedArrivalHint = normalizeAirportHint(arrivalAirportHint);
		String normalizedScheduledHint = scheduledDepartureHint == null ? null : scheduledDepartureHint.trim();

		String cacheKey = normalizedFlightNumber + ":" + departureDate + ":" + nullToEmpty(normalizedDepartureHint)
				+ ":" + nullToEmpty(normalizedArrivalHint) + ":" + nullToEmpty(normalizedScheduledHint);
		CachedResult cached = cache.get(cacheKey);
		if (!refresh && cached != null && Duration.between(cached.cachedAt(), attemptedAt).compareTo(CACHE_TTL) < 0) {
			return withCacheFlag(cached.result(), true);
		}

		try {
			ResponseEntity<JsonNode> response = restClient.get()
					.uri(builder -> builder.path("/flights/number/{flightNumber}/{date}")
							.queryParam("dateLocalRole", "Departure")
							.queryParam("withAircraftImage", false)
							.queryParam("withLocation", false)
							.build(normalizedFlightNumber, departureDate))
					.header("X-RapidAPI-Key", key)
					.header("X-RapidAPI-Host", HOST)
					.retrieve()
					.toEntity(JsonNode.class);
			LOGGER.info("provider={} httpStatus={} correlationId={}", providerName(),
					response.getStatusCode().value(), correlationId);
			ProviderFlightResult result = parseResponse(response.getBody(), normalizedFlightNumber, departureDate,
					normalizedDepartureHint, normalizedArrivalHint, normalizedScheduledHint, Instant.now());
			if (result.successful()) cache.put(cacheKey, new CachedResult(result, Instant.now()));
			return result;
		} catch (RestClientResponseException exception) {
			ProviderFlightResult failure = httpFailure(exception, attemptedAt);
			LOGGER.warn("provider={} httpStatus={} category={} errorCode={} correlationId={}",
					providerName(), exception.getStatusCode().value(), failure.outcome(),
					failure.errorCode(), correlationId);
			return failure;
		} catch (RestClientException exception) {
			ProviderFailureDiagnostics.Failure failure = ProviderFailureDiagnostics.classify(providerName(), exception);
			LOGGER.warn("provider={} httpStatus=none category={} errorCode={} correlationId={}",
					providerName(), failure.category(), failure.errorCode(), correlationId);
			return ProviderFlightResult.failure(providerName(), failure.outcome(), failure.errorCode(), attemptedAt);
		} catch (RuntimeException exception) {
			LOGGER.warn("provider={} httpStatus=200 category=PARSE_ERROR correlationId={}",
					providerName(), correlationId);
			return ProviderFlightResult.failure(providerName(), "PARSE_ERROR", "AERODATABOX_PARSE_ERROR", attemptedAt);
		}
	}

	private ProviderFlightResult parseResponse(JsonNode root, String requestedNumber, LocalDate requestedDate,
			String departureHint, String arrivalHint, String scheduledDepartureHint, Instant retrievedAt) {
		JsonNode records = root != null && root.isArray() ? root : root == null ? null : root.path("departures");
		if (records == null || !records.isArray()) {
			return ProviderFlightResult.failure(providerName(), "PARSE_ERROR", "AERODATABOX_RESPONSE_NOT_ARRAY", retrievedAt);
		}
		if (records.isEmpty()) {
			return ProviderFlightResult.failure(providerName(), ProviderFlightResult.NOT_FOUND,
					"AERODATABOX_FLIGHT_NOT_FOUND", retrievedAt);
		}

		List<ScoredCandidate> matches = new ArrayList<>();
		String firstMismatchNumber = null;
		LocalDate firstMismatchDate = null;
		boolean missingIdentity = false;
		Iterator<JsonNode> iterator = records.iterator();
		while (iterator.hasNext()) {
			JsonNode candidate = iterator.next();
			String number = normalizeFlight(text(candidate, "number"));
			JsonNode departure = candidate.path("departure");
			JsonNode arrival = candidate.path("arrival");
			OffsetDateTime scheduled = parseOffsetDateTime(text(departure.path("scheduledTime"), "local"));
			LocalDate candidateDate = scheduled == null ? null : scheduled.toLocalDate();
			String dep = airportCode(departure);
			String arr = airportCode(arrival);
			if (number.isBlank() || candidateDate == null) {
				missingIdentity = true;
				continue;
			}
			if (!normalizeFlight(requestedNumber).equals(number) || !requestedDate.equals(candidateDate)) {
				if (firstMismatchNumber == null) {
					firstMismatchNumber = number;
					firstMismatchDate = candidateDate;
				}
				continue;
			}
			int score = 100;
			if (!isBlank(departureHint) && departureHint.equalsIgnoreCase(dep)) score += 5;
			if (!isBlank(arrivalHint) && arrivalHint.equalsIgnoreCase(arr)) score += 5;
			score += timeHintScore(scheduled, scheduledDepartureHint);
			matches.add(new ScoredCandidate(candidate, score));
		}
		if (matches.isEmpty()) {
			if (firstMismatchNumber != null) {
				return ProviderFlightResult.identityFailure(providerName(), ProviderFlightResult.IDENTITY_MISMATCH,
						"AERODATABOX_IDENTITY_MISMATCH", firstMismatchNumber, firstMismatchDate, retrievedAt);
			}
			if (missingIdentity) {
				return ProviderFlightResult.identityFailure(providerName(), ProviderFlightResult.IDENTITY_UNVERIFIED,
						"AERODATABOX_IDENTITY_UNVERIFIED", null, null, retrievedAt);
			}
			return ProviderFlightResult.failure(providerName(), ProviderFlightResult.NOT_FOUND,
					"AERODATABOX_FLIGHT_NOT_FOUND", retrievedAt);
		}
		int bestScore = matches.stream().mapToInt(ScoredCandidate::score).max().orElseThrow();
		List<JsonNode> bestMatches = matches.stream().filter(candidate -> candidate.score() == bestScore)
				.map(ScoredCandidate::item).toList();
		long distinctRoutes = bestMatches.stream()
				.map(item -> airportCode(item.path("departure")) + ">" + airportCode(item.path("arrival")))
				.distinct().count();
		if (distinctRoutes > 1) {
			return ProviderFlightResult.failure(providerName(), "PARSE_ERROR", "AERODATABOX_IDENTITY_AMBIGUOUS", retrievedAt);
		}
		JsonNode item = bestMatches.stream().max(Comparator.comparing(this::recordRecency,
				Comparator.nullsFirst(Comparator.naturalOrder()))).orElseThrow();
		JsonNode departure = item.path("departure");
		JsonNode arrival = item.path("arrival");
		OffsetDateTime scheduledDeparture = readTime(departure, "scheduledTime");
		OffsetDateTime estimatedDeparture = firstNonNull(readTime(departure, "revisedTime"),
				readTime(departure, "predictedTime"));
		OffsetDateTime actualDeparture = readTime(departure, "runwayTime");
		OffsetDateTime scheduledArrival = readTime(arrival, "scheduledTime");
		OffsetDateTime estimatedArrival = firstNonNull(readTime(arrival, "revisedTime"),
				readTime(arrival, "predictedTime"));
		OffsetDateTime actualArrival = readTime(arrival, "runwayTime");
		OffsetDateTime departureForDelay = firstNonNull(actualDeparture, estimatedDeparture);
		OffsetDateTime arrivalForDelay = firstNonNull(actualArrival, estimatedArrival);
		Instant sourceUpdatedAt = firstNonNull(parseInstant(text(item, "lastUpdatedUtc")),
				parseInstant(text(item, "updatedAt")), parseInstant(text(departure, "lastUpdatedUtc")));

		return new ProviderFlightResult(providerName(), "SUCCESS", null,
				normalizeFlight(text(item, "number")), scheduledDeparture.toLocalDate(),
				airportCode(departure), airportName(departure), airportCode(arrival), airportName(arrival),
				scheduledDeparture, estimatedDeparture, actualDeparture, scheduledArrival, estimatedArrival,
				actualArrival, readTime(departure, "boardingTime"), normalizeTerminal(text(departure, "terminal")),
				normalizeTerminal(text(arrival, "terminal")),
				clean(text(departure, "gate")), clean(text(departure, "checkInDesk")),
				readDedicatedBagDrop(departure), clean(text(arrival, "baggageBelt")),
				normalizeStatus(text(item, "status")),
				delayMinutes(scheduledDeparture, departureForDelay), delayMinutes(scheduledArrival, arrivalForDelay),
				quality(item, departure), sourceUpdatedAt, retrievedAt, null, text(item, "codeshare"), true, false);
	}

	/* Departure bag drop and arrival baggage reclaim are deliberately kept as separate fields. */
	private String readDedicatedBagDrop(JsonNode departure) {
		return firstNonBlank(text(departure, "dedicatedBagDropDesk"), text(departure, "bagDropDesk"));
	}

	private OffsetDateTime recordRecency(JsonNode item) {
		JsonNode departure = item.path("departure");
		return firstNonNull(readTime(departure, "runwayTime"), readTime(departure, "revisedTime"),
				readTime(departure, "predictedTime"), readTime(departure, "scheduledTime"));
	}

	private ProviderFlightResult httpFailure(RestClientResponseException exception, Instant attemptedAt) {
		int status = exception.getStatusCode().value();
		if (status == 400) {
			BadRequestClassification classification = classifyBadRequest(exception.getResponseBodyAsString());
			return ProviderFlightResult.failure(providerName(), classification.outcome(),
					classification.errorCode(), attemptedAt);
		}
		String outcome = switch (status) {
			case 401 -> ProviderFlightResult.AUTH_ERROR;
			case 403, 429 -> ProviderFlightResult.RATE_LIMITED;
			case 404 -> ProviderFlightResult.NOT_FOUND;
			default -> ProviderFlightResult.UPSTREAM_ERROR;
		};
		return ProviderFlightResult.failure(providerName(), outcome, "AERODATABOX_HTTP_" + status, attemptedAt);
	}

	private BadRequestClassification classifyBadRequest(String responseBody) {
		String errorText = structuredErrorText(responseBody).toLowerCase(Locale.ROOT);
		if (errorText.isBlank()) return unknownBadRequest();

		if (containsAny(errorText, "subscription", "pricing plan", "current plan", "plan does not",
				"tier", "not entitled", "not available for your plan", "provider restriction")) {
			return new BadRequestClassification(ProviderFlightResult.RATE_LIMITED, "PLAN_OR_PROVIDER_RESTRICTION");
		}
		if (containsAny(errorText, "outside the data coverage", "outside data coverage", "out of coverage",
				"outside the supported date", "date range limit", "maximum date range", "history limit")) {
			return new BadRequestClassification(ProviderFlightResult.UPSTREAM_ERROR, "DATE_OUT_OF_COVERAGE");
		}
		if (containsAny(errorText, "invalid flight number", "malformed flight number",
				"flight number format", "invalid searchparam", "invalid search param")) {
			return new BadRequestClassification(ProviderFlightResult.UPSTREAM_ERROR, "AERODATABOX_INVALID_PROVIDER_FLIGHT_NUMBER");
		}
		if (errorText.contains("date") && containsAny(errorText, "invalid", "malformed", "format",
				"must be", "datefromlocal", "datetolocal")) {
			return new BadRequestClassification(ProviderFlightResult.UPSTREAM_ERROR, "AERODATABOX_INVALID_PROVIDER_DATE");
		}
		if (containsAny(errorText, "missing required", "required parameter", "invalid parameter",
				"invalid query", "bad request", "request validation")) {
			return new BadRequestClassification(ProviderFlightResult.UPSTREAM_ERROR, "AERODATABOX_INVALID_PROVIDER_REQUEST");
		}
		return unknownBadRequest();
	}

	private BadRequestClassification unknownBadRequest() {
		return new BadRequestClassification("UPSTREAM_ERROR", "AERODATABOX_HTTP_400");
	}

	private String structuredErrorText(String responseBody) {
		if (responseBody == null || responseBody.isBlank()) return "";
		try {
			JsonNode root = objectMapper.readTree(responseBody);
			StringBuilder result = new StringBuilder();
			appendAllowedErrorFields(root, result, 0);
			return result.length() > 2_000 ? result.substring(0, 2_000) : result.toString();
		} catch (RuntimeException exception) {
			return "";
		}
	}

	private void appendAllowedErrorFields(JsonNode node, StringBuilder output, int depth) {
		if (node == null || depth > 4) return;
		if (node.isObject()) {
			for (String field : List.of("message", "error", "detail")) {
				JsonNode value = node.path(field);
				if (value.isMissingNode() || value.isNull()) continue;
				appendAllowedErrorValue(value, output, depth + 1);
			}
		}
	}

	private void appendAllowedErrorValue(JsonNode value, StringBuilder output, int depth) {
		if (output.length() >= 2_000 || value == null || depth > 4) return;
		if (value.isTextual()) {
			if (!output.isEmpty()) output.append(' ');
			output.append(value.asText(), 0, Math.min(value.asText().length(), 500));
		} else if (value.isObject()) {
			appendAllowedErrorFields(value, output, depth);
		} else if (value.isArray()) {
			for (JsonNode item : value) appendAllowedErrorValue(item, output, depth + 1);
		}
	}

	private boolean containsAny(String value, String... candidates) {
		for (String candidate : candidates) if (value.contains(candidate)) return true;
		return false;
	}

	private int timeHintScore(OffsetDateTime scheduled, String hint) {
		LocalTime hintedTime = parseTimeHint(hint);
		if (scheduled == null || hintedTime == null) return 0;
		long difference = Math.abs(ChronoUnit.MINUTES.between(hintedTime, scheduled.toLocalTime()));
		difference = Math.min(difference, 24 * 60 - difference);
		if (difference <= 30) return 4;
		return difference <= 120 ? 2 : 0;
	}

	private LocalTime parseTimeHint(String value) {
		if (isBlank(value)) return null;
		try {
			return OffsetDateTime.parse(value).toLocalTime();
		} catch (DateTimeParseException ignored) {
			// Try a local date-time or time-only document value.
		}
		try {
			return LocalDateTime.parse(value).toLocalTime();
		} catch (DateTimeParseException ignored) {
			// Continue with time-only parsing.
		}
		try {
			return LocalTime.parse(value);
		} catch (DateTimeParseException ignored) {
			return null;
		}
	}

	private String normalizeAirportHint(String value) {
		if (isBlank(value)) return null;
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		return normalized.matches("[A-Z]{3}") ? normalized : null;
	}

	private ProviderFlightResult withCacheFlag(ProviderFlightResult value, boolean fromCache) {
		return new ProviderFlightResult(value.provider(), value.outcome(), value.errorCode(), value.flightNumber(),
				value.departureDate(), value.departureAirport(), value.departureAirportName(), value.arrivalAirport(),
				value.arrivalAirportName(), value.scheduledDeparture(), value.estimatedDeparture(), value.actualDeparture(),
				value.scheduledArrival(), value.estimatedArrival(), value.actualArrival(), value.boardingTime(),
				value.terminal(), value.arrivalTerminal(), value.gate(), value.checkInDeskOrZone(), value.dedicatedBagDropDesk(),
				value.arrivalBaggageBelt(), value.status(),
				value.departureDelayMinutes(), value.arrivalDelayMinutes(), value.quality(), value.sourceUpdatedAt(),
				value.retrievedAt(), value.sourceUrl(), value.codeshare(), value.live(), fromCache);
	}

	private String airportCode(JsonNode leg) {
		return firstNonBlank(text(leg.path("airport"), "iata"), text(leg.path("airport"), "icao"));
	}

	private String airportName(JsonNode leg) {
		return text(leg.path("airport"), "name");
	}

	private OffsetDateTime readTime(JsonNode leg, String field) {
		JsonNode time = leg.path(field);
		return firstNonNull(parseOffsetDateTime(text(time, "local")), parseOffsetDateTime(text(time, "utc")));
	}

	private OffsetDateTime parseOffsetDateTime(String value) {
		if (isBlank(value)) return null;
		String normalized = value.trim();
		if (normalized.length() > 10 && normalized.charAt(10) == ' ') {
			normalized = normalized.substring(0, 10) + "T" + normalized.substring(11);
		}
		try {
			return OffsetDateTime.parse(normalized);
		} catch (DateTimeParseException exception) {
			return null;
		}
	}

	private Instant parseInstant(String value) {
		try {
			return isBlank(value) ? null : Instant.parse(value);
		} catch (DateTimeParseException exception) {
			OffsetDateTime parsed = parseOffsetDateTime(value);
			return parsed == null ? null : parsed.toInstant();
		}
	}

	private Integer delayMinutes(OffsetDateTime scheduled, OffsetDateTime effective) {
		return scheduled == null || effective == null ? null
				: Math.max(0, Math.toIntExact(ChronoUnit.MINUTES.between(scheduled.toInstant(), effective.toInstant())));
	}

	private String quality(JsonNode item, JsonNode departure) {
		JsonNode quality = item.path("quality").isMissingNode() ? departure.path("quality") : item.path("quality");
		if (quality.isArray()) {
			List<String> values = new ArrayList<>();
			quality.forEach(value -> values.add(value.asText()));
			return String.join(",", values);
		}
		return clean(quality.asText(null));
	}

	private String normalizeTerminal(String value) {
		String clean = clean(value);
		if (clean == null || clean.toLowerCase(Locale.ROOT).startsWith("terminal")) return clean;
		return "Terminal " + clean;
	}

	private String normalizeStatus(String value) {
		if (isBlank(value)) return "UNKNOWN";
		String status = value.trim().toUpperCase(Locale.ROOT);
		if (status.contains("CANCEL")) return "CANCELLED";
		if (status.contains("ARRIV")) return "ARRIVED";
		if (status.contains("DEPART")) return "DEPARTED";
		if (status.contains("BOARD")) return "BOARDING";
		if (status.contains("DELAY")) return "DELAYED";
		if (status.contains("SCHEDULE") || status.contains("ON TIME")) return "ON_TIME";
		return status.replace(' ', '_');
	}

	private String normalizeFlight(String value) {
		return isBlank(value) ? "" : value.replaceAll("[\\s\\-‐‑‒–—−]", "").toUpperCase(Locale.ROOT);
	}

	private String text(JsonNode node, String field) {
		if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) return null;
		return clean(node.path(field).asText());
	}

	private String clean(String value) {
		if (value == null || value.isBlank() || "-".equals(value.trim()) || "null".equalsIgnoreCase(value.trim())) return null;
		return value.trim();
	}

	private String firstNonBlank(String first, String second) {
		return isBlank(first) ? second : first;
	}

	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	@SafeVarargs
	private final <T> T firstNonNull(T... values) {
		for (T value : values) if (value != null) return value;
		return null;
	}

	private record CachedResult(ProviderFlightResult result, Instant cachedAt) {
	}

	private record BadRequestClassification(String outcome, String errorCode) {
	}

	private record ScoredCandidate(JsonNode item, int score) {
	}
}
