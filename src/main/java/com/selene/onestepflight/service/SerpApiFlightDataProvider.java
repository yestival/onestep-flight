package com.selene.onestepflight.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.selene.onestepflight.dto.FlightResponse;
import com.selene.onestepflight.dto.ProviderFlightResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class SerpApiFlightDataProvider implements LiveFlightDataProvider, OperationalFlightDataProvider,
		FlightDateDiscoveryProvider {

	private static final Duration CACHE_TTL = Duration.ofMinutes(5);
	private static final Pattern EXPLICIT_FLIGHT_NUMBER = Pattern.compile(
			"(?i)(?<![A-Z0-9])([A-Z0-9]{2,3})[\\s\\-‐‑‒–—−]*([0-9]{1,4}[A-Z]?)(?![A-Z0-9])");
	private static final Logger LOGGER = LoggerFactory.getLogger(SerpApiFlightDataProvider.class);
	private final Supplier<String> apiKeySupplier;
	private final Map<String, CachedFlight> cache = new ConcurrentHashMap<>();
	private final ObjectMapper objectMapper;
	private final RestClient restClient;
	private final RealIntegrationCallStatus callStatus;

	public SerpApiFlightDataProvider(ObjectMapper objectMapper) {
		this(objectMapper, buildRestClient(), () -> System.getenv("SERPAPI_API_KEY"),
				new RealIntegrationCallStatus());
	}

	@Autowired
	public SerpApiFlightDataProvider(ObjectMapper objectMapper, RealIntegrationCallStatus callStatus) {
		this(objectMapper, buildRestClient(), () -> System.getenv("SERPAPI_API_KEY"), callStatus);
	}

	SerpApiFlightDataProvider(ObjectMapper objectMapper, RestClient restClient,
			Supplier<String> apiKeySupplier) {
		this(objectMapper, restClient, apiKeySupplier, new RealIntegrationCallStatus());
	}

	SerpApiFlightDataProvider(ObjectMapper objectMapper, RestClient restClient,
			Supplier<String> apiKeySupplier, RealIntegrationCallStatus callStatus) {
		this.objectMapper = objectMapper;
		this.restClient = restClient;
		this.apiKeySupplier = apiKeySupplier;
		this.callStatus = callStatus;
	}

	private static RestClient buildRestClient() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(3));
		requestFactory.setReadTimeout(Duration.ofSeconds(7));
		return RestClient.builder()
				.baseUrl("https://serpapi.com")
				.requestFactory(requestFactory)
				.build();
	}

	@Override
	public FlightResponse findFlight(String flightNumber, LocalDate departureDate, boolean refresh) {
		return resolveFlight(flightNumber, departureDate, null, null, null, refresh).flight();
	}

	@Override
	public LocalDate discoverDepartureDate(String flightNumber, String departureAirportHint,
			String arrivalAirportHint, String scheduledDepartureHint) {
		String apiKey = apiKeySupplier.get();
		if (apiKey == null || apiKey.isBlank()) {
			throw new ExternalServiceNotConfiguredException("SERPAPI");
		}
		String normalizedFlightNumber = normalizeFlight(flightNumber);
		if (!normalizedFlightNumber.matches("[A-Z0-9]{2,3}[0-9]{1,4}[A-Z]?")) return null;
		String normalizedDepartureHint = strictDiscoveryAirportHint(departureAirportHint);
		String normalizedArrivalHint = strictDiscoveryAirportHint(arrivalAirportHint);
		LocalTime normalizedTimeHint = isBlank(scheduledDepartureHint)
				? null : parseTimeHint(scheduledDepartureHint);
		if (!isBlank(scheduledDepartureHint) && normalizedTimeHint == null) return null;

		JsonNode response = requestFlightResult(apiKey.trim(), normalizedFlightNumber);
		JsonNode flightResult = response == null ? null : response.path("flight_result");
		return findUniqueDiscoveryDate(flightResult, normalizedFlightNumber,
				normalizedDepartureHint, normalizedArrivalHint, normalizedTimeHint);
	}

	private ResolvedSerpFlight resolveFlight(String flightNumber, LocalDate departureDate,
			String departureAirportHint, String arrivalAirportHint,
			String scheduledDepartureHint, boolean refresh) {
		String apiKey = apiKeySupplier.get();
		if (apiKey == null || apiKey.isBlank()) {
			throw new ExternalServiceNotConfiguredException("SERPAPI");
		}
		apiKey = apiKey.trim();
		if (departureDate == null) {
			throw new ExternalServiceUnavailableException("FLIGHT_DATE_REQUIRED");
		}
		String normalizedFlightNumber = normalizeFlight(flightNumber);
		String normalizedDepartureHint = normalizeAirport(departureAirportHint);
		String normalizedArrivalHint = normalizeAirport(arrivalAirportHint);
		String normalizedScheduledHint = scheduledDepartureHint == null ? null : scheduledDepartureHint.trim();

		String cacheKey = normalizedFlightNumber + ":" + departureDate + ":" + nullToEmpty(normalizedDepartureHint)
				+ ":" + nullToEmpty(normalizedArrivalHint) + ":" + nullToEmpty(normalizedScheduledHint);
		CachedFlight cached = cache.get(cacheKey);
		if (!refresh && cached != null && Duration.between(cached.cachedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
			return new ResolvedSerpFlight(cached.flight(), cached.quality(), true);
		}

		JsonNode response = requestFlightResult(apiKey, normalizedFlightNumber);
		Instant retrievedAt = Instant.now();
		JsonNode flightResult = response == null ? null : response.path("flight_result");
		JsonNode matchedDate = flightResult == null || flightResult.isMissingNode()
				? null : findDate(flightResult, normalizedFlightNumber, departureDate,
						normalizedDepartureHint, normalizedArrivalHint, normalizedScheduledHint);
		ResolvedSerpFlight resolved;
		if (matchedDate != null) {
			resolved = parseFlightResult(matchedDate, normalizedFlightNumber, departureDate, retrievedAt);
		} else {
			RouteHint routeHint = resolveFallbackRoute(flightResult, normalizedFlightNumber,
					normalizedDepartureHint, normalizedArrivalHint);
			if (routeHint == null) {
				throw new ExternalServiceUnavailableException("SERPAPI_ROUTE_INCOMPLETE");
			}
			JsonNode fallback = requestGoogleFlightsSchedule(apiKey, normalizedFlightNumber, departureDate,
					routeHint.departureAirport(), routeHint.arrivalAirport());
			resolved = parseGoogleFlightsSchedule(fallback, normalizedFlightNumber, departureDate,
					routeHint.departureAirport(), routeHint.arrivalAirport(),
					normalizedScheduledHint, retrievedAt);
		}
		cache.put(cacheKey, new CachedFlight(resolved.flight(), resolved.quality(), retrievedAt));
		return resolved;
	}

	private JsonNode requestFlightResult(String apiKey, String flightNumber) {
		String correlationId = UUID.randomUUID().toString();
		try {
			ResponseEntity<JsonNode> response = restClient.get()
					.uri(builder -> builder.path("/search")
							.queryParam("engine", "google")
							.queryParam("q", "Flight " + flightNumber)
							.queryParam("hl", "en")
							.queryParam("api_key", apiKey)
							.build())
					.retrieve()
					.toEntity(JsonNode.class);
			LOGGER.info("provider=SERPAPI httpStatus={} correlationId={}",
					response.getStatusCode().value(), correlationId);
			return validateSerpResponse(response.getBody());
		} catch (RestClientResponseException exception) {
			String errorCode = ProviderRetryAfter.code(httpErrorCode(exception), exception, Instant.now());
			LOGGER.warn("provider=SERPAPI httpStatus={} errorCode={} correlationId={}",
					exception.getStatusCode().value(), errorCode, correlationId);
			throw new ExternalServiceUnavailableException(errorCode);
		} catch (RestClientException exception) {
			ProviderFailureDiagnostics.Failure failure = ProviderFailureDiagnostics.classify(providerName(), exception);
			LOGGER.warn("provider=SERPAPI httpStatus=none category={} errorCode={} correlationId={}",
					failure.category(), failure.errorCode(), correlationId);
			throw new ExternalServiceUnavailableException(failure.errorCode());
		}
	}

	private JsonNode requestGoogleFlightsSchedule(String apiKey, String flightNumber, LocalDate departureDate,
			String departureAirport, String arrivalAirport) {
		String correlationId = UUID.randomUUID().toString();
		ObjectNode selectedFlights = objectMapper.createObjectNode();
		ObjectNode segment = selectedFlights.putArray("outbound").addObject();
		segment.put("flight_number", normalizeFlight(flightNumber));
		segment.put("departure_id", departureAirport.toUpperCase(Locale.ROOT));
		segment.put("arrival_id", arrivalAirport.toUpperCase(Locale.ROOT));
		segment.put("date", departureDate.toString());
		try {
			ResponseEntity<JsonNode> response = restClient.get()
					.uri(builder -> builder.path("/search")
							.queryParam("engine", "google_flights")
							.queryParam("type", "2")
							.queryParam("hl", "en")
							.queryParam("departure_id", departureAirport.toUpperCase(Locale.ROOT))
							.queryParam("arrival_id", arrivalAirport.toUpperCase(Locale.ROOT))
							.queryParam("outbound_date", departureDate)
							.queryParam("selected_flights_json", "{selectedFlightsJson}")
							.queryParam("api_key", apiKey)
							.build(Map.of("selectedFlightsJson", selectedFlights.toString())))
					.retrieve()
					.toEntity(JsonNode.class);
			LOGGER.info("provider=SERPAPI httpStatus={} correlationId={}",
					response.getStatusCode().value(), correlationId);
			return validateSerpResponse(response.getBody());
		} catch (RestClientResponseException exception) {
			String errorCode = ProviderRetryAfter.code(httpErrorCode(exception), exception, Instant.now());
			LOGGER.warn("provider=SERPAPI httpStatus={} errorCode={} correlationId={}",
					exception.getStatusCode().value(), errorCode, correlationId);
			throw new ExternalServiceUnavailableException(errorCode);
		} catch (RestClientException exception) {
			ProviderFailureDiagnostics.Failure failure = ProviderFailureDiagnostics.classify(providerName(), exception);
			LOGGER.warn("provider=SERPAPI httpStatus=none category={} errorCode={} correlationId={}",
					failure.category(), failure.errorCode(), correlationId);
			throw new ExternalServiceUnavailableException(failure.errorCode());
		}
	}

	private JsonNode validateSerpResponse(JsonNode root) {
		String status = textOrNull(root == null ? null : root.path("search_metadata"), "status");
		String error = allowedErrorText(root);
		if (error != null || "ERROR".equalsIgnoreCase(status)) {
			throw new ExternalServiceUnavailableException(classifySerpError(error));
		}
		return root;
	}

	private String classifySerpError(String error) {
		String normalized = error == null ? "" : error.toLowerCase(Locale.ROOT);
		if (containsAny(normalized, "invalid api key", "api key is invalid", "unauthorized")) {
			return "SERPAPI_AUTHENTICATION_FAILED";
		}
		if (containsAny(normalized, "run out of searches", "quota", "searches limit", "account limit")) {
			return "SERPAPI_QUOTA_EXCEEDED";
		}
		if (containsAny(normalized, "no results", "hasn't returned any results", "did not return any results")) {
			return "SERPAPI_NO_RESULTS";
		}
		if (containsAny(normalized, "missing required", "required parameter", "invalid parameter",
				"invalid value", "unable to process the request", "bad request")) {
			return "SERPAPI_INVALID_PROVIDER_REQUEST";
		}
		return "SERPAPI_SEARCH_ERROR";
	}

	private String httpErrorCode(RestClientResponseException exception) {
		int status = exception.getStatusCode().value();
		if (status != 400) return "SERPAPI_HTTP_" + status;
		String structuredError = structuredErrorText(exception.getResponseBodyAsString());
		String classified = classifySerpError(structuredError);
		return "SERPAPI_SEARCH_ERROR".equals(classified) ? "SERPAPI_HTTP_400" : classified;
	}

	private String structuredErrorText(String responseBody) {
		if (responseBody == null || responseBody.isBlank()) return null;
		try {
			return allowedErrorText(objectMapper.readTree(responseBody));
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private String allowedErrorText(JsonNode root) {
		if (root == null) return null;
		StringBuilder result = new StringBuilder();
		appendAllowedErrorFields(root, result, 0);
		return result.isEmpty() ? null : result.toString();
	}

	private void appendAllowedErrorFields(JsonNode node, StringBuilder output, int depth) {
		if (node == null || !node.isObject() || depth > 4 || output.length() >= 2_000) return;
		for (String field : List.of("error", "message", "detail")) {
			JsonNode value = node.path(field);
			if (!value.isMissingNode() && !value.isNull()) appendAllowedErrorValue(value, output, depth + 1);
		}
	}

	private void appendAllowedErrorValue(JsonNode value, StringBuilder output, int depth) {
		if (value == null || depth > 4 || output.length() >= 2_000) return;
		if (value.isTextual()) {
			String text = value.asText();
			if (!output.isEmpty()) output.append(' ');
			output.append(text, 0, Math.min(text.length(), 500));
		} else if (value.isObject()) {
			appendAllowedErrorFields(value, output, depth);
		} else if (value.isArray()) {
			for (JsonNode item : value) appendAllowedErrorValue(item, output, depth + 1);
		}
	}

	private RouteHint resolveFallbackRoute(JsonNode flightResult, String requestedFlightNumber,
			String departureHint, String arrivalHint) {
		String departure = normalizeAirport(departureHint);
		String arrival = normalizeAirport(arrivalHint);

		List<RouteHint> metadataRoutes = new ArrayList<>();
		JsonNode dates = flightResult == null ? null : flightResult.path("dates");
		if (dates != null && dates.isArray()) {
			for (JsonNode date : dates) {
				if (!identityOf(date, "flight_designator", requestedFlightNumber).verified()) continue;
				JsonNode metadata = date.path("metadata");
				String candidateDeparture = normalizeAirport(textOrNull(metadata, "origin"));
				String candidateArrival = normalizeAirport(textOrNull(metadata, "destination"));
				if (candidateDeparture != null && candidateArrival != null) {
					RouteHint candidate = new RouteHint(candidateDeparture, candidateArrival);
					if (!metadataRoutes.contains(candidate)) metadataRoutes.add(candidate);
				}
			}
		}
		if (!metadataRoutes.isEmpty()) {
			int bestScore = metadataRoutes.stream().mapToInt(route -> routeHintScore(route, departure, arrival))
					.max().orElseThrow();
			List<RouteHint> bestRoutes = metadataRoutes.stream()
					.filter(route -> routeHintScore(route, departure, arrival) == bestScore).toList();
			return bestRoutes.size() == 1 ? bestRoutes.get(0) : null;
		}
		return null;
	}

	private int routeHintScore(RouteHint route, String departureHint, String arrivalHint) {
		int score = 0;
		if (departureHint != null && departureHint.equals(route.departureAirport())) score++;
		if (arrivalHint != null && arrivalHint.equals(route.arrivalAirport())) score++;
		return score;
	}

	private String normalizeAirport(String value) {
		if (isBlank(value)) return null;
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		return normalized.matches("[A-Z]{3}") ? normalized : null;
	}

	@Override
	public String providerName() {
		return "SERPAPI";
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
		ProviderFlightResult result;
		try {
			ResolvedSerpFlight resolved = resolveFlight(flightNumber, departureDate,
					departureAirportHint, arrivalAirportHint, scheduledDepartureHint, refresh);
			FlightResponse flight = resolved.flight();
			boolean scheduleOnly = "SCHEDULE".equals(resolved.quality());
			result = new ProviderFlightResult(providerName(), "SUCCESS", null, flight.flightNumber(),
					flight.departureDate(), flight.departureAirport(), flight.departureAirportName(),
					flight.arrivalAirport(), flight.arrivalAirportName(), flight.scheduledDeparture(),
					flight.estimatedDeparture(), flight.actualDeparture(), flight.scheduledArrival(),
					flight.estimatedArrival(), flight.actualArrival(), flight.boardingTime(),
					scheduleOnly ? null : flight.terminal(), flight.arrivalTerminal(), scheduleOnly ? null : flight.gate(),
					scheduleOnly ? null : flight.checkInCounter(), null, null,
					scheduleOnly ? null : flight.status(),
					scheduleOnly ? null : flight.delayMinutes(), scheduleOnly ? null : flight.arrivalDelayMinutes(), resolved.quality(),
					flight.lastUpdated(), flight.dataRetrievedAt(), flight.sourceUrl(), flight.codeshare(),
					!scheduleOnly, resolved.fromCache());
		} catch (ExternalServiceNotConfiguredException exception) {
			result = ProviderFlightResult.failure(providerName(), "NOT_CONFIGURED", "SERPAPI_NOT_CONFIGURED", attemptedAt);
		} catch (IdentityFailureException exception) {
			result = ProviderFlightResult.identityFailure(providerName(), exception.outcome(), exception.code(),
					exception.actualFlightNumber(), exception.actualDepartureDate(), attemptedAt);
		} catch (ExternalServiceUnavailableException exception) {
			result = ProviderFlightResult.failure(providerName(), outcomeFor(exception.code()), exception.code(), attemptedAt);
		} catch (RuntimeException exception) {
			result = ProviderFlightResult.failure(providerName(), "PARSE_ERROR", "SERPAPI_PARSE_ERROR", attemptedAt);
		}
		callStatus.recordSerpApi(result, Instant.now());
		return result;
	}

	private ResolvedSerpFlight parseFlightResult(JsonNode matchedDate, String requestedFlightNumber,
			LocalDate requestedDate, Instant retrievedAt) {
		FlightIdentity identity = identityOf(matchedDate, "flight_designator", requestedFlightNumber);
		if (!identity.verified()) throw identityFailure(identity, parseDatePart(textOrNull(matchedDate, "date")));
		JsonNode departure = matchedDate.path("departure_airport");
		JsonNode arrival = matchedDate.path("arrival_airport");
		OffsetDateTime scheduledDeparture = parseOffsetDateTime(textOrNull(departure, "scheduled_time"));
		JsonNode metadata = matchedDate.path("metadata");
		String status = normalizeStatus(firstText(metadata, "status", matchedDate, "status"));
		int departureDelay = metadata.path("departure_delay").asInt(0);
		int arrivalDelay = metadata.path("arrival_delay").asInt(0);
		OffsetDateTime reportedDeparture = parseOffsetDateTime(textOrNull(departure, "time"));
		OffsetDateTime reportedArrival = parseOffsetDateTime(textOrNull(arrival, "time"));
		boolean departed = containsIgnoreCase(textOrNull(departure, "status"), "departed")
				|| status.startsWith("DEPARTED") || status.startsWith("ARRIVED");
		boolean arrived = containsIgnoreCase(textOrNull(arrival, "status"), "arrived")
				|| status.startsWith("ARRIVED");
		// Cached search results can label a future scheduled/estimated time as an
		// actual event. Do not let that contradiction end a traveller's journey.
		boolean futureDeparture = departed && reportedDeparture != null
				&& reportedDeparture.toInstant().isAfter(retrievedAt);
		boolean futureArrival = arrived && reportedArrival != null
				&& reportedArrival.toInstant().isAfter(retrievedAt);
		if ((futureDeparture && (status.startsWith("DEPARTED") || status.startsWith("ARRIVED")))
				|| (futureArrival && status.startsWith("ARRIVED"))) {
			status = "UNKNOWN";
		}
		departed = departed && !futureDeparture;
		arrived = arrived && !futureArrival;
		String departureAirport = firstNonBlank(textOrNull(departure, "id"), textOrNull(metadata, "origin"));
		String arrivalAirport = firstNonBlank(textOrNull(arrival, "id"), textOrNull(metadata, "destination"));
		if (departureAirport == null || arrivalAirport == null) {
			throw new ExternalServiceUnavailableException("SERPAPI_ROUTE_INCOMPLETE");
		}

		LocalDate matchedDepartureDate = parseDatePart(textOrNull(matchedDate, "date"));
		if (!requestedDate.equals(matchedDepartureDate)) {
			throw new IdentityFailureException(ProviderFlightResult.IDENTITY_MISMATCH,
					"SERPAPI_DEPARTURE_DATE_MISMATCH", identity.actualFlightNumber(), matchedDepartureDate);
		}
		FlightResponse flight = new FlightResponse(
				identity.actualFlightNumber(),
				matchedDepartureDate,
				departureAirport,
				firstNonBlank(textOrNull(departure, "city"), departureAirport),
				arrivalAirport,
				firstNonBlank(textOrNull(arrival, "city"), arrivalAirport),
				scheduledDeparture,
				departed ? null : reportedDeparture,
				departed ? reportedDeparture : null,
				parseOffsetDateTime(textOrNull(arrival, "scheduled_time")),
				arrived ? null : reportedArrival,
				arrived ? reportedArrival : null,
				null,
				cleanLocation(textOrNull(departure, "terminal"), "Terminal "),
				cleanLocation(textOrNull(arrival, "terminal"), "Terminal "),
				cleanLocation(textOrNull(departure, "gate"), ""),
				null,
				status,
				departureDelay,
				arrivalDelay,
				parseInstant(textOrNull(matchedDate, "updated_at")),
				retrievedAt,
				"SERPAPI_GOOGLE_FLIGHT_RESULT",
				textOrNull(matchedDate, "source_url"),
				identity.codeshares(),
				true,
				true,
				false);
		return new ResolvedSerpFlight(flight,
				scheduledDeparture == null ? "STATUS_METADATA" : "LIVE", false);
	}

	private ResolvedSerpFlight parseGoogleFlightsSchedule(JsonNode root, String requestedFlightNumber,
			LocalDate requestedDate, String departureHint, String arrivalHint,
			String scheduledDepartureHint, Instant retrievedAt) {
		List<JsonNode> segments = new ArrayList<>();
		collectGoogleFlightSegments(root == null ? null : root.path("selected_flights"), segments);
		collectGoogleFlightSegments(root == null ? null : root.path("best_flights"), segments);
		collectGoogleFlightSegments(root == null ? null : root.path("other_flights"), segments);
		List<ScoredJsonCandidate> matches = new ArrayList<>();
		FlightIdentity firstMissing = null;
		FlightIdentity firstMismatch = null;
		LocalDate firstMismatchDate = null;
		for (JsonNode segment : segments) {
			FlightIdentity identity = identityOf(segment, "flight_number", requestedFlightNumber);
			LocalDate candidateDate = parseDatePart(textOrNull(segment.path("departure_airport"), "time"));
			if (identity.missing()) {
				if (firstMissing == null) firstMissing = identity;
				continue;
			}
			if (!identity.verified() || candidateDate == null || !requestedDate.equals(candidateDate)) {
				if (firstMismatch == null) {
					firstMismatch = identity;
					firstMismatchDate = candidateDate;
				}
				continue;
			}
			int score = 100;
			String candidateDeparture = textOrNull(segment.path("departure_airport"), "id");
			String candidateArrival = textOrNull(segment.path("arrival_airport"), "id");
			if (!isBlank(departureHint) && departureHint.equalsIgnoreCase(candidateDeparture)) score += 5;
			if (!isBlank(arrivalHint) && arrivalHint.equalsIgnoreCase(candidateArrival)) score += 5;
			score += timeHintScore(textOrNull(segment.path("departure_airport"), "time"),
					scheduledDepartureHint);
			matches.add(new ScoredJsonCandidate(segment, score));
		}
		if (matches.isEmpty()) {
			String error = root == null ? null : textOrNull(root, "error");
			if (segments.isEmpty() && containsIgnoreCase(error, "no results")) {
				throw new ExternalServiceUnavailableException("SERPAPI_NO_RESULTS");
			}
			if (firstMissing != null) throw identityFailure(firstMissing, null);
			if (firstMismatch != null) throw new IdentityFailureException(
					ProviderFlightResult.IDENTITY_MISMATCH, "SERPAPI_IDENTITY_MISMATCH",
					firstMismatch.actualFlightNumber(), firstMismatchDate);
			throw new ExternalServiceUnavailableException("SERPAPI_NO_RESULTS");
		}

		int bestScore = matches.stream().mapToInt(ScoredJsonCandidate::score).max().orElseThrow();
		List<JsonNode> bestMatches = matches.stream().filter(candidate -> candidate.score() == bestScore)
				.map(ScoredJsonCandidate::item).toList();
		long distinctRoutes = bestMatches.stream()
				.map(segment -> normalizeAirport(textOrNull(segment.path("departure_airport"), "id")) + ">"
						+ normalizeAirport(textOrNull(segment.path("arrival_airport"), "id")))
				.distinct().count();
		if (distinctRoutes > 1) {
			throw new ExternalServiceUnavailableException("SERPAPI_IDENTITY_AMBIGUOUS");
		}
		JsonNode segment = bestMatches.get(0);
		FlightIdentity identity = identityOf(segment, "flight_number", requestedFlightNumber);
		JsonNode departure = segment.path("departure_airport");
		JsonNode arrival = segment.path("arrival_airport");
		LocalDate matchedDepartureDate = parseDatePart(textOrNull(departure, "time"));
		String selectedDeparture = normalizeAirport(textOrNull(departure, "id"));
		String selectedArrival = normalizeAirport(textOrNull(arrival, "id"));
		if (selectedDeparture == null || selectedArrival == null) {
			throw new ExternalServiceUnavailableException("SERPAPI_ROUTE_INCOMPLETE");
		}
		FlightResponse flight = new FlightResponse(
				identity.actualFlightNumber(),
				matchedDepartureDate,
				selectedDeparture,
				firstNonBlank(textOrNull(departure, "name"), selectedDeparture),
				selectedArrival,
				firstNonBlank(textOrNull(arrival, "name"), selectedArrival),
				parseAirportDateTime(textOrNull(departure, "time"), selectedDeparture),
				null,
				null,
				parseAirportDateTime(textOrNull(arrival, "time"), selectedArrival),
				null,
				null,
				null,
				cleanLocation(textOrNull(departure, "terminal"), "Terminal "),
				cleanLocation(textOrNull(arrival, "terminal"), "Terminal "),
				null,
				null,
				null,
				0,
				0,
				null,
				retrievedAt,
				"SERPAPI_GOOGLE_FLIGHTS_SCHEDULE",
				textOrNull(root == null ? null : root.path("search_metadata"), "google_flights_url"),
				identity.codeshares(),
				false,
				true,
				false);
		return new ResolvedSerpFlight(flight, "SCHEDULE", false);
	}

	private void collectGoogleFlightSegments(JsonNode itineraries, List<JsonNode> output) {
		if (itineraries == null || !itineraries.isArray()) return;
		for (JsonNode itinerary : itineraries) {
			JsonNode flights = itinerary.path("flights");
			if (flights.isArray()) flights.forEach(output::add);
		}
	}

	private LocalDate parseDatePart(String value) {
		if (value == null || value.length() < 10) return null;
		try {
			return LocalDate.parse(value.substring(0, 10));
		} catch (DateTimeParseException exception) {
			return null;
		}
	}

	private JsonNode findDate(JsonNode flightResult, String requestedFlightNumber, LocalDate requestedDate,
			String departureHint, String arrivalHint, String scheduledDepartureHint) {
		JsonNode dates = flightResult.path("dates");
		if (!dates.isArray()) throw new ExternalServiceUnavailableException("SERPAPI_RESPONSE_INVALID");
		if (dates.isEmpty()) throw new ExternalServiceUnavailableException("SERPAPI_NO_RESULTS");
		List<ScoredJsonCandidate> matches = new ArrayList<>();
		FlightIdentity firstMissing = null;
		FlightIdentity firstMismatch = null;
		LocalDate firstMismatchDate = null;
		boolean verifiedIdentityExists = false;
		for (JsonNode item : dates) {
			FlightIdentity identity = identityOf(item, "flight_designator", requestedFlightNumber);
			LocalDate candidateDate = parseDatePart(textOrNull(item, "date"));
			if (identity.missing()) {
				if (firstMissing == null) firstMissing = identity;
				continue;
			}
			if (!identity.verified()) {
				if (firstMismatch == null) {
					firstMismatch = identity;
					firstMismatchDate = candidateDate;
				}
				continue;
			}
			verifiedIdentityExists = true;
			if (candidateDate == null || !requestedDate.equals(candidateDate)) continue;
			JsonNode metadata = item.path("metadata");
			String candidateDeparture = firstNonBlank(textOrNull(item.path("departure_airport"), "id"),
					textOrNull(metadata, "origin"));
			String candidateArrival = firstNonBlank(textOrNull(item.path("arrival_airport"), "id"),
					textOrNull(metadata, "destination"));
			int score = 100;
			if (!isBlank(departureHint) && departureHint.equalsIgnoreCase(candidateDeparture)) score += 5;
			if (!isBlank(arrivalHint) && arrivalHint.equalsIgnoreCase(candidateArrival)) score += 5;
			score += timeHintScore(parseOffsetDateTime(textOrNull(item.path("departure_airport"), "scheduled_time")),
					scheduledDepartureHint);
			matches.add(new ScoredJsonCandidate(item, score));
		}
		if (matches.isEmpty()) {
			if (verifiedIdentityExists) return null;
			if (firstMissing != null) throw identityFailure(firstMissing, null);
			if (firstMismatch != null) throw new IdentityFailureException(
					ProviderFlightResult.IDENTITY_MISMATCH, "SERPAPI_IDENTITY_MISMATCH",
					firstMismatch.actualFlightNumber(), firstMismatchDate);
			throw new ExternalServiceUnavailableException("SERPAPI_NO_RESULTS");
		}
		int bestScore = matches.stream().mapToInt(ScoredJsonCandidate::score).max().orElseThrow();
		List<JsonNode> bestMatches = matches.stream().filter(candidate -> candidate.score() == bestScore)
				.map(ScoredJsonCandidate::item).toList();
		long distinctRoutes = bestMatches.stream().map(item -> {
			JsonNode metadata = item.path("metadata");
			return firstNonBlank(textOrNull(item.path("departure_airport"), "id"), textOrNull(metadata, "origin"))
					+ ">" + firstNonBlank(textOrNull(item.path("arrival_airport"), "id"),
							textOrNull(metadata, "destination"));
		}).distinct().count();
		if (distinctRoutes > 1) throw new ExternalServiceUnavailableException("SERPAPI_IDENTITY_AMBIGUOUS");
		return bestMatches.get(0);
	}

	private LocalDate findUniqueDiscoveryDate(JsonNode flightResult, String requestedFlightNumber,
			String departureHint, String arrivalHint, LocalTime scheduledDepartureHint) {
		if (flightResult == null) return null;
		JsonNode dates = flightResult.path("dates");
		if (!dates.isArray()) return null;
		List<ScoredDateCandidate> matches = new ArrayList<>();
		for (JsonNode item : dates) {
			if (!identityOf(item, "flight_designator", requestedFlightNumber).verified()) continue;
			LocalDate candidateDate = parseDatePart(textOrNull(item, "date"));
			if (candidateDate == null) continue;
			JsonNode metadata = item.path("metadata");
			String candidateDeparture = normalizeAirport(firstNonBlank(
					textOrNull(item.path("departure_airport"), "id"), textOrNull(metadata, "origin")));
			String candidateArrival = normalizeAirport(firstNonBlank(
					textOrNull(item.path("arrival_airport"), "id"), textOrNull(metadata, "destination")));
			if (departureHint != null && !departureHint.equals(candidateDeparture)) continue;
			if (arrivalHint != null && !arrivalHint.equals(candidateArrival)) continue;

			int score = 1;
			if (scheduledDepartureHint != null) {
				String candidateTimeValue = firstNonBlank(
						textOrNull(item.path("departure_airport"), "scheduled_time"),
						textOrNull(item.path("departure_airport"), "time"));
				LocalTime candidateTime = parseTimeHint(candidateTimeValue);
				score = timeHintScore(candidateTime, scheduledDepartureHint);
				if (score == 0) continue;
			}
			matches.add(new ScoredDateCandidate(candidateDate, score));
		}
		if (matches.isEmpty()) return null;
		int bestScore = matches.stream().mapToInt(ScoredDateCandidate::score).max().orElseThrow();
		List<LocalDate> bestDates = matches.stream()
				.filter(candidate -> candidate.score() == bestScore)
				.map(ScoredDateCandidate::date).distinct().toList();
		return bestDates.size() == 1 ? bestDates.get(0) : null;
	}

	private String strictDiscoveryAirportHint(String value) {
		if (isBlank(value)) return null;
		String normalized = normalizeAirport(value);
		if (normalized == null) throw new IllegalArgumentException("invalid airport hint");
		return normalized;
	}

	private String normalizeStatus(String value) {
		if (value == null) return "UNKNOWN";
		String status = value.toUpperCase(Locale.ROOT);
		if (status.contains("CANCEL")) return "CANCELLED";
		if (status.contains("ARRIV")) return "ARRIVED";
		if (status.contains("DEPART")) return "DEPARTED";
		if (status.contains("BOARD")) return "BOARDING";
		if (status.contains("DELAY") || status.contains("LATE")) return "DELAYED";
		if (status.contains("ON_TIME") || status.contains("ON TIME") || status.contains("SCHEDULED")) return "ON_TIME";
		return status.replace(' ', '_');
	}

	private OffsetDateTime parseOffsetDateTime(String value) {
		try {
			return value == null ? null : OffsetDateTime.parse(value);
		} catch (DateTimeParseException exception) {
			return null;
		}
	}

	private OffsetDateTime parseAirportDateTime(String value, String airport) {
		OffsetDateTime withOffset = parseOffsetDateTime(value);
		if (withOffset != null || value == null) return withOffset;
		String normalized = value.trim();
		if (normalized.length() > 10 && normalized.charAt(10) == ' ') {
			normalized = normalized.substring(0, 10) + "T" + normalized.substring(11);
		}
		ZoneId zone = airportZone(airport);
		if (zone == null) return null;
		try {
			return LocalDateTime.parse(normalized).atZone(zone).toOffsetDateTime();
		} catch (DateTimeParseException exception) {
			return null;
		}
	}

	private int timeHintScore(OffsetDateTime candidate, String hint) {
		return candidate == null ? 0 : timeHintScore(candidate.toLocalTime(), parseTimeHint(hint));
	}

	private int timeHintScore(String candidate, String hint) {
		return timeHintScore(parseTimeHint(candidate), parseTimeHint(hint));
	}

	private int timeHintScore(LocalTime candidate, LocalTime hint) {
		if (candidate == null || hint == null) return 0;
		long difference = Math.abs(java.time.temporal.ChronoUnit.MINUTES.between(hint, candidate));
		difference = Math.min(difference, 24 * 60 - difference);
		if (difference <= 30) return 4;
		return difference <= 120 ? 2 : 0;
	}

	private LocalTime parseTimeHint(String value) {
		if (isBlank(value)) return null;
		try {
			return OffsetDateTime.parse(value).toLocalTime();
		} catch (DateTimeParseException ignored) {
			// Try local date-time and time-only forms below.
		}
		String normalized = value.trim();
		if (normalized.length() > 10 && normalized.charAt(10) == ' ') {
			normalized = normalized.substring(0, 10) + "T" + normalized.substring(11);
		}
		try {
			return LocalDateTime.parse(normalized).toLocalTime();
		} catch (DateTimeParseException ignored) {
			// Continue with time-only parsing.
		}
		try {
			return LocalTime.parse(normalized);
		} catch (DateTimeParseException ignored) {
			return null;
		}
	}

	private ZoneId airportZone(String airport) {
		if (isBlank(airport)) return null;
		return switch (airport.toUpperCase(Locale.ROOT)) {
			case "PEK", "PKX", "SHA", "PVG", "CAN", "SZX", "CTU", "TFU", "XIY", "XMN",
					"HFE", "HGH", "NKG", "WUH", "CKG", "CSX", "TAO", "TSN" -> ZoneId.of("Asia/Shanghai");
			case "LHR", "LGW", "LCY" -> ZoneId.of("Europe/London");
			case "JFK", "LGA", "EWR" -> ZoneId.of("America/New_York");
			case "CDG", "ORY" -> ZoneId.of("Europe/Paris");
			case "FRA", "MUC" -> ZoneId.of("Europe/Berlin");
			case "AMS" -> ZoneId.of("Europe/Amsterdam");
			case "DXB" -> ZoneId.of("Asia/Dubai");
			default -> null;
		};
	}

	private Instant parseInstant(String value) {
		try {
			return value == null ? null : Instant.parse(value);
		} catch (DateTimeParseException exception) {
			return null;
		}
	}

	private String firstText(JsonNode first, String firstField, JsonNode second, String secondField) {
		return firstNonBlank(textOrNull(first, firstField), textOrNull(second, secondField));
	}

	private String textOrNull(JsonNode node, String field) {
		if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) return null;
		String value = node.path(field).asText().trim();
		return value.isBlank() ? null : value;
	}

	private String cleanLocation(String value, String prefix) {
		if (value == null || "-".equals(value)) return null;
		if (!prefix.isBlank() && !value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
			return prefix + value;
		}
		return value;
	}

	private String firstNonBlank(String first, String second) {
		return first != null && !first.isBlank() ? first : second;
	}

	private String normalizeFlight(String value) {
		return value == null ? "" : value.replaceAll("[\\s\\-‐‑‒–—−]", "").toUpperCase(Locale.ROOT);
	}

	private FlightIdentity identityOf(JsonNode candidate, String identityField, String requestedFlightNumber) {
		String actual = normalizeFlight(textOrNull(candidate, identityField));
		String codeshares = explicitCodeshares(candidate);
		if (actual.isBlank()) return new FlightIdentity(null, codeshares, false, true);
		String requested = normalizeFlight(requestedFlightNumber);
		boolean verified = requested.equals(actual) || containsExplicitCodeshare(codeshares, requested);
		return new FlightIdentity(actual, codeshares, verified, false);
	}

	private String explicitCodeshares(JsonNode candidate) {
		if (candidate == null || !candidate.isObject()) return null;
		LinkedHashSet<String> values = new LinkedHashSet<>();
		for (String field : List.of("codeshare", "codeshares", "code_share", "code_shares")) {
			collectExplicitFlightNumbers(candidate.path(field), values, 0);
		}
		return values.isEmpty() ? null : String.join(",", values);
	}

	private void collectExplicitFlightNumbers(JsonNode node, LinkedHashSet<String> output, int depth) {
		if (node == null || node.isMissingNode() || node.isNull() || depth > 4) return;
		if (node.isTextual()) {
			Matcher matcher = EXPLICIT_FLIGHT_NUMBER.matcher(node.asText());
			while (matcher.find()) output.add(normalizeFlight(matcher.group(1) + matcher.group(2)));
			return;
		}
		if (node.isArray()) {
			for (JsonNode item : node) collectExplicitFlightNumbers(item, output, depth + 1);
			return;
		}
		if (node.isObject()) {
			node.properties().forEach(entry -> collectExplicitFlightNumbers(entry.getValue(), output, depth + 1));
		}
	}

	private boolean containsExplicitCodeshare(String codeshares, String requestedFlightNumber) {
		if (codeshares == null || requestedFlightNumber == null) return false;
		for (String codeshare : codeshares.split(",")) {
			if (requestedFlightNumber.equals(normalizeFlight(codeshare))) return true;
		}
		return false;
	}

	private IdentityFailureException identityFailure(FlightIdentity identity, LocalDate actualDepartureDate) {
		return new IdentityFailureException(
				identity.missing() ? ProviderFlightResult.IDENTITY_UNVERIFIED : ProviderFlightResult.IDENTITY_MISMATCH,
				identity.missing() ? "SERPAPI_IDENTITY_UNVERIFIED" : "SERPAPI_IDENTITY_MISMATCH",
				identity.actualFlightNumber(), actualDepartureDate);
	}

	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private boolean containsIgnoreCase(String value, String expected) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
	}

	private String outcomeFor(String code) {
		if (code.contains(";retryAt=")) code = code.substring(0, code.indexOf(";retryAt="));
		if (code.endsWith("_401") || code.contains("AUTHENTICATION_FAILED")) return ProviderFlightResult.AUTH_ERROR;
		if (code.endsWith("_403") || code.contains("QUOTA_EXCEEDED") || code.endsWith("_429")) {
			return ProviderFlightResult.RATE_LIMITED;
		}
		if (code.contains("NO_RESULTS")) return ProviderFlightResult.NOT_FOUND;
		if (code.contains("TIMEOUT")) return ProviderFlightResult.TIMEOUT;
		return code.contains("PARSE") || code.contains("ROUTE_INCOMPLETE")
				|| code.contains("RESPONSE_INVALID") || code.contains("IDENTITY_AMBIGUOUS")
				? ProviderFlightResult.PARSE_ERROR : ProviderFlightResult.UPSTREAM_ERROR;
	}

	private boolean containsAny(String value, String... candidates) {
		for (String candidate : candidates) if (value.contains(candidate)) return true;
		return false;
	}

	private record CachedFlight(FlightResponse flight, String quality, Instant cachedAt) {
	}

	private record ResolvedSerpFlight(FlightResponse flight, String quality, boolean fromCache) {
	}

	private record RouteHint(String departureAirport, String arrivalAirport) {
	}

	private record ScoredJsonCandidate(JsonNode item, int score) {
	}

	private record ScoredDateCandidate(LocalDate date, int score) {
	}

	private record FlightIdentity(String actualFlightNumber, String codeshares,
			boolean verified, boolean missing) {
	}

	private static final class IdentityFailureException extends ExternalServiceUnavailableException {
		private final String outcome;
		private final String actualFlightNumber;
		private final LocalDate actualDepartureDate;

		private IdentityFailureException(String outcome, String code, String actualFlightNumber,
				LocalDate actualDepartureDate) {
			super(code);
			this.outcome = outcome;
			this.actualFlightNumber = actualFlightNumber;
			this.actualDepartureDate = actualDepartureDate;
		}

		private String outcome() { return outcome; }
		private String actualFlightNumber() { return actualFlightNumber; }
		private LocalDate actualDepartureDate() { return actualDepartureDate; }
	}
}
