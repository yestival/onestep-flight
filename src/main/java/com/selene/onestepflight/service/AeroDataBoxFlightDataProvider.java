package com.selene.onestepflight.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.selene.onestepflight.dto.ProviderFlightResult;

import tools.jackson.databind.JsonNode;

@Component
public class AeroDataBoxFlightDataProvider implements OperationalFlightDataProvider {

	private static final Duration CACHE_TTL = Duration.ofMinutes(5);
	private static final String HOST = "aerodatabox.p.rapidapi.com";
	private static final Logger LOGGER = LoggerFactory.getLogger(AeroDataBoxFlightDataProvider.class);
	private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();
	private final RestClient restClient;

	public AeroDataBoxFlightDataProvider() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(3));
		requestFactory.setReadTimeout(Duration.ofSeconds(5));
		this.restClient = RestClient.builder()
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
		Instant attemptedAt = Instant.now();
		String key = System.getenv("AERODATABOX_RAPIDAPI_KEY");
		if (key == null || key.isBlank()) {
			return ProviderFlightResult.failure(providerName(), "NOT_CONFIGURED", "AERODATABOX_NOT_CONFIGURED", attemptedAt);
		}
		key = key.trim();
		if (departureDate == null) {
			return ProviderFlightResult.failure(providerName(), "NOT_FOUND", "AERODATABOX_DATE_REQUIRED", attemptedAt);
		}

		String cacheKey = flightNumber + ":" + departureDate + ":" + nullToEmpty(departureAirportHint)
				+ ":" + nullToEmpty(arrivalAirportHint);
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
							.build(flightNumber, departureDate))
					.header("X-RapidAPI-Key", key)
					.header("X-RapidAPI-Host", HOST)
					.retrieve()
					.toEntity(JsonNode.class);
			LOGGER.info("provider={} stage=flight-request httpStatus={} contentType={}", providerName(),
					response.getStatusCode().value(), response.getHeaders().getContentType());
			ProviderFlightResult result = parseResponse(response.getBody(), flightNumber, departureDate,
					departureAirportHint, arrivalAirportHint, Instant.now());
			if (result.successful()) cache.put(cacheKey, new CachedResult(result, Instant.now()));
			return result;
		} catch (RestClientResponseException exception) {
			LOGGER.warn("provider={} stage=flight-request httpStatus={} rootCauseType={} category=HTTP_REJECTED message={}",
					providerName(), exception.getStatusCode().value(), exception.getClass().getName(),
					ProviderFailureDiagnostics.safeMessage(exception));
			return httpFailure(exception.getStatusCode().value(), attemptedAt);
		} catch (RestClientException exception) {
			ProviderFailureDiagnostics.Failure failure = ProviderFailureDiagnostics.classify(providerName(), exception);
			LOGGER.warn("provider={} stage=flight-request httpStatus=none rootCauseType={} category={} message={}",
					providerName(), failure.rootCauseType(), failure.category(), failure.safeMessage());
			return ProviderFlightResult.failure(providerName(), failure.outcome(), failure.errorCode(), attemptedAt);
		} catch (RuntimeException exception) {
			LOGGER.warn("provider={} stage=response-parse httpStatus=200 rootCauseType={} category=PARSE_ERROR message={}",
					providerName(), exception.getClass().getName(), ProviderFailureDiagnostics.safeMessage(exception));
			return ProviderFlightResult.failure(providerName(), "PARSE_ERROR", "AERODATABOX_PARSE_ERROR", attemptedAt);
		}
	}

	private ProviderFlightResult parseResponse(JsonNode root, String requestedNumber, LocalDate requestedDate,
			String departureHint, String arrivalHint, Instant retrievedAt) {
		JsonNode records = root != null && root.isArray() ? root : root == null ? null : root.path("departures");
		if (records == null || !records.isArray()) {
			return ProviderFlightResult.failure(providerName(), "PARSE_ERROR", "AERODATABOX_RESPONSE_NOT_ARRAY", retrievedAt);
		}

		List<JsonNode> matches = new ArrayList<>();
		Iterator<JsonNode> iterator = records.iterator();
		while (iterator.hasNext()) {
			JsonNode candidate = iterator.next();
			String number = normalizeFlight(text(candidate, "number"));
			JsonNode departure = candidate.path("departure");
			JsonNode arrival = candidate.path("arrival");
			OffsetDateTime scheduled = readTime(departure, "scheduledTime");
			String dep = airportCode(departure);
			String arr = airportCode(arrival);
			if (!normalizeFlight(requestedNumber).equals(number)) continue;
			if (scheduled == null || !requestedDate.equals(scheduled.toLocalDate())) continue;
			if (!isBlank(departureHint) && !departureHint.equalsIgnoreCase(dep)) continue;
			if (!isBlank(arrivalHint) && !arrivalHint.equalsIgnoreCase(arr)) continue;
			matches.add(candidate);
		}
		if (matches.isEmpty()) {
			return ProviderFlightResult.failure(providerName(), "NOT_FOUND", "AERODATABOX_FLIGHT_NOT_FOUND", retrievedAt);
		}
		long distinctRoutes = matches.stream()
				.map(item -> airportCode(item.path("departure")) + ">" + airportCode(item.path("arrival")))
				.distinct().count();
		if (distinctRoutes > 1) {
			return ProviderFlightResult.failure(providerName(), "PARSE_ERROR", "AERODATABOX_IDENTITY_AMBIGUOUS", retrievedAt);
		}
		JsonNode item = matches.stream().max(Comparator.comparing(this::recordRecency,
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
				normalizeFlight(text(item, "number")), requestedDate,
				airportCode(departure), airportName(departure), airportCode(arrival), airportName(arrival),
				scheduledDeparture, estimatedDeparture, actualDeparture, scheduledArrival, estimatedArrival,
				actualArrival, readTime(departure, "boardingTime"), normalizeTerminal(text(departure, "terminal")),
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

	private ProviderFlightResult httpFailure(int status, Instant attemptedAt) {
		String outcome = switch (status) {
			case 401 -> "AUTHENTICATION_FAILED";
			case 403 -> "FORBIDDEN_OR_QUOTA";
			case 404 -> "NOT_FOUND";
			case 429 -> "RATE_LIMITED";
			default -> "UPSTREAM_ERROR";
		};
		return ProviderFlightResult.failure(providerName(), outcome, "AERODATABOX_HTTP_" + status, attemptedAt);
	}

	private ProviderFlightResult withCacheFlag(ProviderFlightResult value, boolean fromCache) {
		return new ProviderFlightResult(value.provider(), value.outcome(), value.errorCode(), value.flightNumber(),
				value.departureDate(), value.departureAirport(), value.departureAirportName(), value.arrivalAirport(),
				value.arrivalAirportName(), value.scheduledDeparture(), value.estimatedDeparture(), value.actualDeparture(),
				value.scheduledArrival(), value.estimatedArrival(), value.actualArrival(), value.boardingTime(),
				value.terminal(), value.gate(), value.checkInDeskOrZone(), value.dedicatedBagDropDesk(),
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
		return isBlank(value) ? "" : value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
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
}
