package com.selene.onestepflight.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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

import com.selene.onestepflight.dto.FlightResponse;
import com.selene.onestepflight.dto.ProviderFlightResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class SerpApiFlightDataProvider implements LiveFlightDataProvider, OperationalFlightDataProvider {

	private static final Duration CACHE_TTL = Duration.ofMinutes(5);
	private static final Logger LOGGER = LoggerFactory.getLogger(SerpApiFlightDataProvider.class);
	private final Map<String, CachedFlight> cache = new ConcurrentHashMap<>();
	private final ObjectMapper objectMapper;
	private final RestClient restClient;

	public SerpApiFlightDataProvider(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(3));
		requestFactory.setReadTimeout(Duration.ofSeconds(7));
		this.restClient = RestClient.builder()
				.baseUrl("https://serpapi.com")
				.requestFactory(requestFactory)
				.build();
	}

	@Override
	public FlightResponse findFlight(String flightNumber, LocalDate departureDate, boolean refresh) {
		return resolveFlight(flightNumber, departureDate, null, null, refresh).flight();
	}

	private ResolvedSerpFlight resolveFlight(String flightNumber, LocalDate departureDate,
			String departureAirportHint, String arrivalAirportHint, boolean refresh) {
		String apiKey = System.getenv("SERPAPI_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			throw new ExternalServiceNotConfiguredException("SERPAPI");
		}
		apiKey = apiKey.trim();
		if (departureDate == null) {
			throw new ExternalServiceUnavailableException("FLIGHT_DATE_REQUIRED");
		}

		String cacheKey = flightNumber + ":" + departureDate + ":" + nullToEmpty(departureAirportHint)
				+ ":" + nullToEmpty(arrivalAirportHint);
		CachedFlight cached = cache.get(cacheKey);
		if (!refresh && cached != null && Duration.between(cached.cachedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
			return new ResolvedSerpFlight(cached.flight(), cached.quality(), true);
		}

		JsonNode response = requestFlightResult(apiKey, flightNumber);
		Instant retrievedAt = Instant.now();
		JsonNode flightResult = response == null ? null : response.path("flight_result");
		JsonNode matchedDate = flightResult == null || flightResult.isMissingNode()
				? null : findDate(flightResult.path("dates"), departureDate);
		ResolvedSerpFlight resolved;
		if (matchedDate != null) {
			resolved = parseFlightResult(matchedDate, flightNumber, departureDate, retrievedAt);
		} else {
			if (isBlank(departureAirportHint) || isBlank(arrivalAirportHint)) {
				throw new ExternalServiceUnavailableException("SERPAPI_FALLBACK_ROUTE_REQUIRED");
			}
			JsonNode fallback = requestGoogleFlightsSchedule(apiKey, flightNumber, departureDate,
					departureAirportHint, arrivalAirportHint);
			resolved = parseGoogleFlightsSchedule(fallback, flightNumber, departureDate,
					departureAirportHint, arrivalAirportHint, retrievedAt);
		}
		cache.put(cacheKey, new CachedFlight(resolved.flight(), resolved.quality(), retrievedAt));
		return resolved;
	}

	private JsonNode requestFlightResult(String apiKey, String flightNumber) {
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
			LOGGER.info("provider=SERPAPI stage=flight-result httpStatus={} contentType={}",
					response.getStatusCode().value(), response.getHeaders().getContentType());
			return response.getBody();
		} catch (RestClientResponseException exception) {
			throw new ExternalServiceUnavailableException(httpErrorCode("SERPAPI", exception.getStatusCode().value()));
		} catch (RestClientException exception) {
			throw new ExternalServiceUnavailableException("SERPAPI_REQUEST_FAILED");
		}
	}

	private JsonNode requestGoogleFlightsSchedule(String apiKey, String flightNumber, LocalDate departureDate,
			String departureAirport, String arrivalAirport) {
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
			LOGGER.info("provider=SERPAPI stage=google-flights-fallback httpStatus={} contentType={}",
					response.getStatusCode().value(), response.getHeaders().getContentType());
			return response.getBody();
		} catch (RestClientResponseException exception) {
			throw new ExternalServiceUnavailableException(httpErrorCode("SERPAPI", exception.getStatusCode().value()));
		} catch (RestClientException exception) {
			throw new ExternalServiceUnavailableException("SERPAPI_REQUEST_FAILED");
		}
	}

	@Override
	public String providerName() {
		return "SERPAPI";
	}

	@Override
	public ProviderFlightResult findFlight(String flightNumber, LocalDate departureDate,
			String departureAirportHint, String arrivalAirportHint, boolean refresh) {
		Instant attemptedAt = Instant.now();
		try {
			ResolvedSerpFlight resolved = resolveFlight(flightNumber, departureDate,
					departureAirportHint, arrivalAirportHint, refresh);
			FlightResponse flight = resolved.flight();
			if (!matchesRoute(flight, departureAirportHint, arrivalAirportHint)) {
				return ProviderFlightResult.failure(providerName(), "NOT_FOUND", "SERPAPI_IDENTITY_MISMATCH", attemptedAt);
			}
			boolean scheduleOnly = "SCHEDULE".equals(resolved.quality());
			return new ProviderFlightResult(providerName(), "SUCCESS", null, flight.flightNumber(),
					flight.departureDate(), flight.departureAirport(), flight.departureAirportName(),
					flight.arrivalAirport(), flight.arrivalAirportName(), flight.scheduledDeparture(),
					flight.estimatedDeparture(), flight.actualDeparture(), flight.scheduledArrival(),
					flight.estimatedArrival(), flight.actualArrival(), flight.boardingTime(),
					scheduleOnly ? null : flight.terminal(), scheduleOnly ? null : flight.gate(),
					scheduleOnly ? null : flight.checkInCounter(), null, null,
					scheduleOnly ? null : flight.status(),
					scheduleOnly ? null : flight.delayMinutes(), scheduleOnly ? null : flight.arrivalDelayMinutes(), resolved.quality(),
					flight.lastUpdated(), flight.dataRetrievedAt(), flight.sourceUrl(), flight.codeshare(),
					!scheduleOnly, resolved.fromCache());
		} catch (ExternalServiceNotConfiguredException exception) {
			return ProviderFlightResult.failure(providerName(), "NOT_CONFIGURED", "SERPAPI_NOT_CONFIGURED", attemptedAt);
		} catch (ExternalServiceUnavailableException exception) {
			return ProviderFlightResult.failure(providerName(), outcomeFor(exception.code()), exception.code(), attemptedAt);
		} catch (RuntimeException exception) {
			return ProviderFlightResult.failure(providerName(), "PARSE_ERROR", "SERPAPI_PARSE_ERROR", attemptedAt);
		}
	}

	private ResolvedSerpFlight parseFlightResult(JsonNode matchedDate, String requestedFlightNumber,
			LocalDate requestedDate, Instant retrievedAt) {
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
		String departureAirport = firstNonBlank(textOrNull(departure, "id"), textOrNull(metadata, "origin"));
		String arrivalAirport = firstNonBlank(textOrNull(arrival, "id"), textOrNull(metadata, "destination"));
		if (departureAirport == null || arrivalAirport == null) {
			throw new ExternalServiceUnavailableException("SERPAPI_ROUTE_INCOMPLETE");
		}

		FlightResponse flight = new FlightResponse(
				requestedFlightNumber,
				requestedDate,
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
				cleanLocation(textOrNull(departure, "gate"), ""),
				null,
				status,
				departureDelay,
				arrivalDelay,
				parseInstant(textOrNull(matchedDate, "updated_at")),
				retrievedAt,
				"SERPAPI_GOOGLE_FLIGHT_RESULT",
				textOrNull(matchedDate, "source_url"),
				textOrNull(matchedDate, "codeshare"),
				true,
				true,
				false);
		return new ResolvedSerpFlight(flight,
				scheduledDeparture == null ? "STATUS_METADATA" : "LIVE", false);
	}

	private ResolvedSerpFlight parseGoogleFlightsSchedule(JsonNode root, String requestedFlightNumber,
			LocalDate requestedDate, String departureHint, String arrivalHint, Instant retrievedAt) {
		List<JsonNode> segments = new ArrayList<>();
		collectGoogleFlightSegments(root == null ? null : root.path("selected_flights"), segments);
		collectGoogleFlightSegments(root == null ? null : root.path("best_flights"), segments);
		collectGoogleFlightSegments(root == null ? null : root.path("other_flights"), segments);
		List<JsonNode> matches = segments.stream()
				.filter(segment -> normalizeFlight(requestedFlightNumber)
						.equals(normalizeFlight(textOrNull(segment, "flight_number"))))
				.filter(segment -> departureHint.equalsIgnoreCase(
						textOrNull(segment.path("departure_airport"), "id")))
				.filter(segment -> arrivalHint.equalsIgnoreCase(
						textOrNull(segment.path("arrival_airport"), "id")))
				.filter(segment -> requestedDate.toString().equals(
						datePart(textOrNull(segment.path("departure_airport"), "time"))))
				.toList();
		if (matches.isEmpty()) {
			String error = root == null ? null : textOrNull(root, "error");
			String code = segments.isEmpty() && containsIgnoreCase(error, "no results")
					? "SERPAPI_NO_RESULTS" : "SERPAPI_IDENTITY_MISMATCH";
			throw new ExternalServiceUnavailableException(code);
		}

		JsonNode segment = matches.get(0);
		JsonNode departure = segment.path("departure_airport");
		JsonNode arrival = segment.path("arrival_airport");
		FlightResponse flight = new FlightResponse(
				requestedFlightNumber,
				requestedDate,
				departureHint.toUpperCase(Locale.ROOT),
				firstNonBlank(textOrNull(departure, "name"), departureHint.toUpperCase(Locale.ROOT)),
				arrivalHint.toUpperCase(Locale.ROOT),
				firstNonBlank(textOrNull(arrival, "name"), arrivalHint.toUpperCase(Locale.ROOT)),
				parseAirportDateTime(textOrNull(departure, "time"), departureHint),
				null,
				null,
				parseAirportDateTime(textOrNull(arrival, "time"), arrivalHint),
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				0,
				0,
				null,
				retrievedAt,
				"SERPAPI_GOOGLE_FLIGHTS_SCHEDULE",
				textOrNull(root == null ? null : root.path("search_metadata"), "google_flights_url"),
				null,
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

	private String datePart(String value) {
		return value == null || value.length() < 10 ? null : value.substring(0, 10);
	}

	private JsonNode findDate(JsonNode dates, LocalDate requestedDate) {
		if (!dates.isArray()) return null;
		Iterator<JsonNode> iterator = dates.iterator();
		while (iterator.hasNext()) {
			JsonNode item = iterator.next();
			if (requestedDate.toString().equals(item.path("date").asText())) return item;
		}
		return null;
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
		return value == null ? "" : value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
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

	private boolean matchesRoute(FlightResponse flight, String departureHint, String arrivalHint) {
		return (departureHint == null || departureHint.isBlank() || departureHint.equalsIgnoreCase(flight.departureAirport()))
				&& (arrivalHint == null || arrivalHint.isBlank() || arrivalHint.equalsIgnoreCase(flight.arrivalAirport()));
	}

	private String outcomeFor(String code) {
		if (code.endsWith("_401")) return "AUTHENTICATION_FAILED";
		if (code.endsWith("_403")) return "FORBIDDEN_OR_QUOTA";
		if (code.endsWith("_404") || code.contains("NO_RESULTS") || code.contains("IDENTITY_MISMATCH")) {
			return "NOT_FOUND";
		}
		if (code.endsWith("_429")) return "RATE_LIMITED";
		if (code.contains("TIMEOUT")) return "TIMEOUT";
		return code.contains("PARSE") || code.contains("ROUTE_REQUIRED") || code.contains("ROUTE_INCOMPLETE")
				? "PARSE_ERROR" : "UPSTREAM_ERROR";
	}

	private String httpErrorCode(String provider, int status) {
		return provider + "_HTTP_" + status;
	}

	private record CachedFlight(FlightResponse flight, String quality, Instant cachedAt) {
	}

	private record ResolvedSerpFlight(FlightResponse flight, String quality, boolean fromCache) {
	}
}
