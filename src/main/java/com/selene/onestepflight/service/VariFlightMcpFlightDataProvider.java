package com.selene.onestepflight.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.selene.onestepflight.dto.ProviderFlightResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
@ConditionalOnProperty(name = "onestep.providers.variflight.enabled", havingValue = "true")
public class VariFlightMcpFlightDataProvider implements OperationalFlightDataProvider {

	private static final Duration CACHE_TTL = Duration.ofMinutes(5);
	private static final String ENDPOINT = "https://ai.variflight.com/servers/tripmatch/mcp";
	private static final Logger LOGGER = LoggerFactory.getLogger(VariFlightMcpFlightDataProvider.class);
	private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();
	private final ObjectMapper objectMapper;
	private final RestClient restClient;

	public VariFlightMcpFlightDataProvider(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(5));
		requestFactory.setReadTimeout(Duration.ofSeconds(35));
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	@Override
	public String providerName() {
		return "VARIFLIGHT";
	}

	@Override
	public ProviderFlightResult findFlight(String flightNumber, LocalDate departureDate,
			String departureAirportHint, String arrivalAirportHint, boolean refresh) {
		Instant attemptedAt = Instant.now();
		String apiKey = System.getenv("VARIFLIGHT_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			return ProviderFlightResult.failure(providerName(), "NOT_CONFIGURED", "VARIFLIGHT_NOT_CONFIGURED", attemptedAt);
		}
		apiKey = apiKey.trim();
		if (departureDate == null) {
			return ProviderFlightResult.failure(providerName(), "NOT_FOUND", "VARIFLIGHT_DATE_REQUIRED", attemptedAt);
		}
		String cacheKey = flightNumber + ":" + departureDate + ":" + nullToEmpty(departureAirportHint)
				+ ":" + nullToEmpty(arrivalAirportHint);
		CachedResult cached = cache.get(cacheKey);
		if (!refresh && cached != null && Duration.between(cached.cachedAt(), attemptedAt).compareTo(CACHE_TTL) < 0) {
			return withCacheFlag(cached.result(), true);
		}

		boolean responseWasSse = false;
		try {
			ResponseEntity<String> response = restClient.post()
					.uri(ENDPOINT)
					.header("X-API-Key", apiKey)
					.contentType(MediaType.APPLICATION_JSON)
					.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
					.body(buildToolCall(flightNumber, departureDate, departureAirportHint, arrivalAirportHint))
					.retrieve()
					.toEntity(String.class);
			MediaType contentType = response.getHeaders().getContentType();
			responseWasSse = contentType != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType);
			LOGGER.info("provider={} stage=tools/call httpStatus={} contentType={} sse={}", providerName(),
					response.getStatusCode().value(), contentType, responseWasSse);
			ProviderFlightResult result = parseMcpResponse(response.getBody(), flightNumber, departureDate,
					departureAirportHint, arrivalAirportHint, Instant.now(), responseWasSse);
			if (result.successful()) cache.put(cacheKey, new CachedResult(result, Instant.now()));
			return result;
		} catch (RestClientResponseException exception) {
			LOGGER.warn("provider={} stage=tools/call httpStatus={} rootCauseType={} category=HTTP_REJECTED message={}",
					providerName(), exception.getStatusCode().value(), exception.getClass().getName(),
					ProviderFailureDiagnostics.safeMessage(exception));
			return httpFailure(exception.getStatusCode().value(), attemptedAt);
		} catch (RestClientException exception) {
			ProviderFailureDiagnostics.Failure failure = ProviderFailureDiagnostics.classify(providerName(), exception);
			LOGGER.warn("provider={} stage=tools/call httpStatus=none rootCauseType={} category={} message={}",
					providerName(), failure.rootCauseType(), failure.category(), failure.safeMessage());
			return ProviderFlightResult.failure(providerName(), failure.outcome(), failure.errorCode(), attemptedAt);
		} catch (RuntimeException exception) {
			String errorCode = responseWasSse ? "VARIFLIGHT_SSE_PARSE_FAILED" : "VARIFLIGHT_JSON_PARSE_FAILED";
			LOGGER.warn("provider={} stage=response-parse httpStatus=200 rootCauseType={} category={} message={}",
					providerName(), exception.getClass().getName(), errorCode,
					ProviderFailureDiagnostics.safeMessage(exception));
			return ProviderFlightResult.failure(providerName(), "PARSE_ERROR", errorCode, attemptedAt);
		}
	}

	private ObjectNode buildToolCall(String flightNumber, LocalDate departureDate,
			String departureAirportHint, String arrivalAirportHint) {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("jsonrpc", "2.0");
		root.put("id", "onestep-flight-query");
		root.put("method", "tools/call");
		ObjectNode params = root.putObject("params");
		params.put("name", "searchFlightsByNumber");
		ObjectNode arguments = params.putObject("arguments");
		arguments.put("fnum", flightNumber);
		arguments.put("date", departureDate.toString());
		if (!isBlank(departureAirportHint)) arguments.put("dep", departureAirportHint.toUpperCase(Locale.ROOT));
		if (!isBlank(arrivalAirportHint)) arguments.put("arr", arrivalAirportHint.toUpperCase(Locale.ROOT));
		return root;
	}

	private ProviderFlightResult parseMcpResponse(String body, String requestedNumber, LocalDate requestedDate,
			String departureHint, String arrivalHint, Instant retrievedAt, boolean sse) {
		if (isBlank(body)) {
			return ProviderFlightResult.failure(providerName(), "PARSE_ERROR", "VARIFLIGHT_EMPTY_RESPONSE", retrievedAt);
		}
		List<JsonNode> envelopes = parseEnvelopes(body);
		if (envelopes.isEmpty()) {
			return ProviderFlightResult.failure(providerName(), "PARSE_ERROR",
					sse ? "VARIFLIGHT_SSE_PARSE_FAILED" : "VARIFLIGHT_JSON_PARSE_FAILED", retrievedAt);
		}
		for (JsonNode envelope : envelopes) {
			if (!envelope.path("error").isMissingNode()) {
				return ProviderFlightResult.failure(providerName(), "UPSTREAM_ERROR", "VARIFLIGHT_MCP_ERROR", retrievedAt);
			}
		}
		List<JsonNode> payloads = new ArrayList<>(envelopes);
		for (JsonNode envelope : envelopes) collectNestedContent(envelope, payloads);
		logResponseShape(envelopes, payloads, body);
		ProviderFlightResult embeddedFailure = classifyEmbeddedFailure(payloads, retrievedAt);
		if (embeddedFailure != null) return embeddedFailure;
		if (envelopes.stream().anyMatch(envelope -> envelope.path("result").path("isError").asBoolean(false))) {
			return ProviderFlightResult.failure(providerName(), "UPSTREAM_ERROR", "VARIFLIGHT_TOOL_ERROR", retrievedAt);
		}

		List<JsonNode> flightObjects = new ArrayList<>();
		for (JsonNode payload : payloads) collectFlightObjects(payload, flightObjects);
		if (flightObjects.isEmpty()) {
			if (hasExplicitNoResults(payloads)) {
				return ProviderFlightResult.failure(providerName(), "NOT_FOUND", "VARIFLIGHT_NO_RESULTS", retrievedAt);
			}
			return ProviderFlightResult.failure(providerName(), "PARSE_ERROR",
					"VARIFLIGHT_UNRECOGNIZED_RESPONSE", retrievedAt);
		}
		List<JsonNode> matches = flightObjects.stream()
				.filter(item -> normalizeFlight(requestedNumber).equals(normalizeFlight(findText(item,
						"fnum", "flightNumber", "flightNo", "flight_no", "flight_number", "FlightNo"))))
				.filter(item -> dateMatches(item, requestedDate))
				.filter(item -> routeMatches(item, departureHint, arrivalHint))
				.toList();
		if (matches.isEmpty()) {
			return ProviderFlightResult.failure(providerName(), "NOT_FOUND",
					"VARIFLIGHT_IDENTITY_MISMATCH", retrievedAt);
		}
		long distinctRoutes = matches.stream().map(this::routeIdentity).distinct().count();
		if (distinctRoutes > 1) {
			return ProviderFlightResult.failure(providerName(), "PARSE_ERROR", "VARIFLIGHT_IDENTITY_AMBIGUOUS", retrievedAt);
		}
		JsonNode item = matches.stream().max(Comparator.comparing(this::candidateRecency,
				Comparator.nullsFirst(Comparator.naturalOrder()))).orElseThrow();
		String departure = findText(item, "dep", "depAirportCode", "departureAirportCode", "departureAirport",
				"orgCode", "FlightDepcode");
		String arrival = findText(item, "arr", "arrAirportCode", "arrivalAirportCode", "arrivalAirport",
				"dstCode", "FlightArrcode");
		OffsetDateTime scheduledDeparture = findAirportTime(item, departure, "scheduledDeparture", "scheduledDepartureTime",
				"scheduleDepTime", "depScheduledTime", "planDepTime", "scheduledDepTime", "FlightDeptimePlanDate");
		OffsetDateTime estimatedDeparture = findAirportTime(item, departure, "estimatedDeparture", "estimatedDepartureTime",
				"estimatedDepTime", "predictedDeparture", "revisedDeparture", "FlightDeptimeReadyDate");
		OffsetDateTime actualDeparture = findAirportTime(item, departure, "actualDeparture", "actualDepartureTime", "actualDepTime",
				"FlightDeptimeDate");
		OffsetDateTime scheduledArrival = findAirportTime(item, arrival, "scheduledArrival", "scheduledArrivalTime",
				"scheduleArrTime", "arrScheduledTime", "planArrTime", "scheduledArrTime", "FlightArrtimePlanDate");
		OffsetDateTime estimatedArrival = findAirportTime(item, arrival, "estimatedArrival", "estimatedArrivalTime",
				"estimatedArrTime", "predictedArrival", "revisedArrival", "FlightArrtimeReadyDate");
		OffsetDateTime actualArrival = findAirportTime(item, arrival, "actualArrival", "actualArrivalTime", "actualArrTime",
				"FlightArrtimeDate");
		Instant sourceUpdated = findInstant(item, "updatedAt", "lastUpdated", "lastUpdatedAt", "dataUpdatedAt");

		return new ProviderFlightResult(providerName(), "SUCCESS", null, normalizeFlight(findText(item,
				"fnum", "flightNumber", "flightNo", "flight_no", "flight_number", "FlightNo")), requestedDate,
				upper(departure), findText(item, "depAirportName", "departureAirportName", "orgAirportName"),
				upper(arrival), findText(item, "arrAirportName", "arrivalAirportName", "dstAirportName"),
				scheduledDeparture, estimatedDeparture, actualDeparture, scheduledArrival, estimatedArrival,
				actualArrival, findAirportTime(item, departure, "boardingTime", "boardTime"),
				normalizeTerminal(findText(item, "depTerminal", "departureTerminal", "terminal",
						"FlightDepTerminal", "DepTerminal")),
				clean(findText(item, "depGate", "departureGate", "gate", "BoardGate")),
				clean(findText(item, "checkInCounter", "checkinCounter", "checkInDesk", "check_in_counter",
						"CheckinTable")),
				clean(findText(item, "dedicatedBagDropDesk", "bagDropDesk")),
				null,
				normalizeStatus(findText(item, "flightStatus", "status", "FlightState")),
				delayMinutes(scheduledDeparture, firstNonNull(actualDeparture, estimatedDeparture)),
				delayMinutes(scheduledArrival, firstNonNull(actualArrival, estimatedArrival)),
				"LIVE", sourceUpdated, retrievedAt, null,
				findText(item, "codeshare", "codeShare"), true, false);
	}

	private List<JsonNode> parseEnvelopes(String body) {
		List<JsonNode> values = new ArrayList<>();
		String trimmed = stripMarkdownFence(body);
		if (trimmed.startsWith("{") || trimmed.startsWith("[")) addJson(trimmed, values);
		for (String line : body.split("\\R")) {
			String value = line.trim();
			if (value.startsWith("data:")) {
				value = value.substring(5).trim();
				if (!value.isBlank() && !"[DONE]".equals(value)) addJson(value, values);
			}
		}
		return values;
	}

	private void collectNestedContent(JsonNode node, List<JsonNode> output) {
		JsonNode content = node.path("result").path("content");
		if (!content.isArray()) return;
		for (JsonNode item : content) {
			JsonNode text = item.path("text");
			if (!text.isTextual()) continue;
			output.add(text);
			addJson(text.asText(), output);
		}
	}

	private void addJson(String value, List<JsonNode> output) {
		String structured = extractStructuredValue(stripMarkdownFence(value));
		if (structured == null) return;
		try {
			output.add(objectMapper.readTree(structured));
		} catch (RuntimeException ignored) {
			try {
				output.add(objectMapper.readTree(pythonLiteralToJson(structured)));
			} catch (RuntimeException alsoIgnored) {
				// The original text remains in payloads for explicit error/no-results classification.
			}
		}
	}

	private String extractStructuredValue(String value) {
		if (isBlank(value)) return null;
		int objectStart = value.indexOf('{');
		int arrayStart = value.indexOf('[');
		int start = objectStart < 0 ? arrayStart : arrayStart < 0 ? objectStart : Math.min(objectStart, arrayStart);
		if (start < 0) return null;
		char opening = value.charAt(start);
		char closing = opening == '{' ? '}' : ']';
		int end = value.lastIndexOf(closing);
		return end < start ? null : value.substring(start, end + 1);
	}

	private String pythonLiteralToJson(String value) {
		StringBuilder json = new StringBuilder(value.length() + 16);
		boolean inSingleQuotedString = false;
		for (int index = 0; index < value.length(); index++) {
			char current = value.charAt(index);
			if (inSingleQuotedString) {
				if (current == '\\' && index + 1 < value.length()) {
					char next = value.charAt(++index);
					if (next == '\'') json.append('\'');
					else if (next == '"') json.append("\\\"");
					else json.append('\\').append(next);
				} else if (current == '\'') {
					json.append('"');
					inSingleQuotedString = false;
				} else if (current == '"') {
					json.append("\\\"");
				} else {
					json.append(current);
				}
			} else if (current == '\'') {
				json.append('"');
				inSingleQuotedString = true;
			} else if (value.startsWith("None", index)) {
				json.append("null");
				index += 3;
			} else if (value.startsWith("True", index)) {
				json.append("true");
				index += 3;
			} else if (value.startsWith("False", index)) {
				json.append("false");
				index += 4;
			} else {
				json.append(current);
			}
		}
		return json.toString();
	}

	private String stripMarkdownFence(String value) {
		String trimmed = value == null ? "" : value.trim();
		if (!trimmed.startsWith("```")) return trimmed;
		int firstLineEnd = trimmed.indexOf('\n');
		if (firstLineEnd < 0) return trimmed;
		String withoutOpeningFence = trimmed.substring(firstLineEnd + 1).trim();
		return withoutOpeningFence.endsWith("```")
				? withoutOpeningFence.substring(0, withoutOpeningFence.length() - 3).trim()
				: withoutOpeningFence;
	}

	private ProviderFlightResult classifyEmbeddedFailure(List<JsonNode> payloads, Instant retrievedAt) {
		List<String> texts = new ArrayList<>();
		for (JsonNode payload : payloads) collectTextValues(payload, texts);
		for (String text : texts) {
			String normalized = text.toLowerCase(Locale.ROOT);
			if (normalized.contains("[http 401]") || normalized.contains("invalid api key")) {
				return ProviderFlightResult.failure(providerName(), ProviderFlightResult.AUTH_ERROR,
						"VARIFLIGHT_TOOL_AUTHENTICATION_FAILED", retrievedAt);
			}
			if (normalized.contains("[http 403]") || normalized.contains("insufficient balance")
					|| normalized.contains("account has been disabled")) {
				return ProviderFlightResult.failure(providerName(), ProviderFlightResult.RATE_LIMITED,
						"VARIFLIGHT_TOOL_FORBIDDEN_OR_QUOTA", retrievedAt);
			}
			if (normalized.contains("[http 429]")) {
				return ProviderFlightResult.failure(providerName(), "RATE_LIMITED",
						"VARIFLIGHT_TOOL_RATE_LIMITED", retrievedAt);
			}
			if (normalized.startsWith("error executing tool")) {
				return ProviderFlightResult.failure(providerName(), "UPSTREAM_ERROR",
						"VARIFLIGHT_TOOL_ERROR", retrievedAt);
			}
		}
		return null;
	}

	private void collectTextValues(JsonNode node, List<String> output) {
		if (node == null) return;
		if (node.isTextual()) {
			output.add(node.asText());
		} else if (node.isObject()) {
			node.properties().forEach(entry -> collectTextValues(entry.getValue(), output));
		} else if (node.isArray()) {
			node.forEach(child -> collectTextValues(child, output));
		}
	}

	private boolean hasExplicitNoResults(List<JsonNode> payloads) {
		List<String> texts = new ArrayList<>();
		for (JsonNode payload : payloads) collectTextValues(payload, texts);
		for (String text : texts) {
			String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
			if (normalized.contains("no flights") || normalized.contains("no matching flight")
					|| normalized.contains("0 flights") || normalized.contains("0 results")
					|| normalized.contains("zero results") || normalized.contains("未查询到航班")
					|| normalized.contains("没有匹配的航班") || normalized.contains("暂无航班")) {
				return true;
			}
		}
		for (JsonNode payload : payloads) {
			if (payload.isArray() && payload.size() == 0) return true;
			if (!payload.isObject()) continue;
			for (String countField : List.of("count", "total", "totalCount", "total_count")) {
				JsonNode count = payload.path(countField);
				if (count.isNumber() && count.asInt() == 0) return true;
			}
			for (String listField : List.of("flights", "records", "items", "data", "list")) {
				JsonNode list = payload.path(listField);
				if (list.isArray() && list.size() == 0) return true;
			}
		}
		return false;
	}

	private void logResponseShape(List<JsonNode> envelopes, List<JsonNode> payloads, String body) {
		List<String> contentTypes = new ArrayList<>();
		int contentCount = 0;
		for (JsonNode envelope : envelopes) {
			JsonNode content = envelope.path("result").path("content");
			if (!content.isArray()) continue;
			contentCount += content.size();
			for (JsonNode item : content) {
				String type = directText(item, "type");
				if (!isBlank(type)) contentTypes.add(type);
			}
		}
		List<String> topLevelFields = new ArrayList<>();
		for (JsonNode payload : payloads) {
			if (!payload.isObject()) continue;
			payload.properties().forEach(entry -> {
				if (topLevelFields.size() < 16 && !topLevelFields.contains(entry.getKey())) {
					topLevelFields.add(entry.getKey());
				}
			});
		}
		String summary = ProviderFailureDiagnostics.safeMessage(new IllegalArgumentException(body));
		LOGGER.info("provider={} stage=response-shape contentCount={} contentTypes={} topLevelFields={} summary={}",
				providerName(), contentCount, contentTypes, topLevelFields, summary);
	}

	private void collectFlightObjects(JsonNode node, List<JsonNode> output) {
		if (node == null) return;
		if (node.isObject()) {
			if (!isBlank(directText(node, "fnum", "flightNumber", "flightNo", "flight_no", "flight_number",
					"FlightNo"))) {
				output.add(node);
			}
			node.properties().forEach(entry -> collectFlightObjects(entry.getValue(), output));
		} else if (node.isArray()) {
			node.forEach(child -> collectFlightObjects(child, output));
		}
	}

	private boolean dateMatches(JsonNode item, LocalDate requestedDate) {
		String raw = findText(item, "date", "flightDate", "departureDate", "depDate",
				"FlightDate", "FlightDeptimePlanDate", "scheduledDeparture", "scheduledDepartureTime");
		if (isBlank(raw)) return false;
		String normalized = raw.replaceAll("[^0-9]", "");
		return requestedDate.toString().replace("-", "").equals(normalized.length() >= 8 ? normalized.substring(0, 8) : normalized);
	}

	private boolean routeMatches(JsonNode item, String departureHint, String arrivalHint) {
		String dep = findText(item, "dep", "depAirportCode", "departureAirportCode", "departureAirport",
				"orgCode", "FlightDepcode");
		String arr = findText(item, "arr", "arrAirportCode", "arrivalAirportCode", "arrivalAirport",
				"dstCode", "FlightArrcode");
		return (isBlank(departureHint) || departureHint.equalsIgnoreCase(dep))
				&& (isBlank(arrivalHint) || arrivalHint.equalsIgnoreCase(arr));
	}

	private String routeIdentity(JsonNode item) {
		return upper(findText(item, "dep", "depAirportCode", "departureAirportCode", "departureAirport",
				"orgCode", "FlightDepcode"))
				+ ">" + upper(findText(item, "arr", "arrAirportCode", "arrivalAirportCode", "arrivalAirport",
						"dstCode", "FlightArrcode"));
	}

	private OffsetDateTime candidateRecency(JsonNode item) {
		return firstNonNull(findTime(item, "actualDeparture", "actualDepTime"),
				findTime(item, "estimatedDeparture", "estimatedDepTime"),
				findTime(item, "scheduledDeparture", "scheduleDepTime", "planDepTime"));
	}

	private String findText(JsonNode node, String... aliases) {
		String direct = directText(node, aliases);
		if (!isBlank(direct)) return direct;
		if (node == null) return null;
		if (node.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> iterator = node.properties().iterator();
			while (iterator.hasNext()) {
				String found = findText(iterator.next().getValue(), aliases);
				if (!isBlank(found)) return found;
			}
		} else if (node.isArray()) {
			for (JsonNode child : node) {
				String found = findText(child, aliases);
				if (!isBlank(found)) return found;
			}
		}
		return null;
	}

	private String directText(JsonNode node, String... aliases) {
		if (node == null || !node.isObject()) return null;
		Map<String, JsonNode> fields = new LinkedHashMap<>();
		node.properties().forEach(entry -> fields.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue()));
		for (String alias : aliases) {
			JsonNode value = fields.get(alias.toLowerCase(Locale.ROOT));
			if (value != null && value.isValueNode() && !value.isNull()) {
				String text = clean(value.asText());
				if (text != null) return text;
			}
		}
		return null;
	}

	private OffsetDateTime findTime(JsonNode node, String... aliases) {
		return parseOffsetDateTime(findText(node, aliases));
	}

	private OffsetDateTime findAirportTime(JsonNode node, String airport, String... aliases) {
		return parseOffsetDateTime(findText(node, aliases), airport);
	}

	private Instant findInstant(JsonNode node, String... aliases) {
		String value = findText(node, aliases);
		if (isBlank(value)) return null;
		try {
			return Instant.parse(value);
		} catch (DateTimeParseException exception) {
			OffsetDateTime parsed = parseOffsetDateTime(value);
			return parsed == null ? null : parsed.toInstant();
		}
	}

	private OffsetDateTime parseOffsetDateTime(String value) {
		return parseOffsetDateTime(value, null);
	}

	private OffsetDateTime parseOffsetDateTime(String value, String airport) {
		if (isBlank(value)) return null;
		String normalized = value.trim();
		if (normalized.length() > 10 && normalized.charAt(10) == ' ') {
			normalized = normalized.substring(0, 10) + "T" + normalized.substring(11);
		}
		try {
			return OffsetDateTime.parse(normalized);
		} catch (DateTimeParseException exception) {
			ZoneId zone = airportZone(airport);
			if (zone == null) return null;
			try {
				return LocalDateTime.parse(normalized).atZone(zone).toOffsetDateTime();
			} catch (DateTimeParseException ignored) {
				return null;
			}
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

	private Integer delayMinutes(OffsetDateTime scheduled, OffsetDateTime effective) {
		return scheduled == null || effective == null ? null
				: Math.max(0, Math.toIntExact(ChronoUnit.MINUTES.between(scheduled.toInstant(), effective.toInstant())));
	}

	private ProviderFlightResult httpFailure(int status, Instant attemptedAt) {
		String outcome = switch (status) {
			case 401 -> ProviderFlightResult.AUTH_ERROR;
			case 403 -> ProviderFlightResult.RATE_LIMITED;
			case 404 -> "NOT_FOUND";
			case 429 -> "RATE_LIMITED";
			default -> "UPSTREAM_ERROR";
		};
		return ProviderFlightResult.failure(providerName(), outcome, "VARIFLIGHT_HTTP_" + status, attemptedAt);
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

	private String normalizeTerminal(String value) {
		String clean = clean(value);
		if (clean == null || clean.toLowerCase(Locale.ROOT).startsWith("terminal")) return clean;
		return "Terminal " + clean;
	}

	private String normalizeStatus(String value) {
		if (isBlank(value)) return null;
		String status = value.trim().toUpperCase(Locale.ROOT);
		if (status.contains("CANCEL") || status.contains("取消")) return "CANCELLED";
		if (status.contains("ARRIV") || status.contains("到达")) return "ARRIVED";
		if (status.contains("DEPART") || status.contains("起飞")) return "DEPARTED";
		if (status.contains("BOARD") || status.contains("登机")) return "BOARDING";
		if (status.contains("DELAY") || status.contains("延误")) return "DELAYED";
		if (status.contains("SCHEDULE") || status.contains("EXPECTED") || status.contains("ON TIME")
				|| status.contains("计划") || status.contains("预计") || status.contains("正常")) return "ON_TIME";
		return status.replace(' ', '_');
	}

	private String normalizeFlight(String value) {
		return isBlank(value) ? "" : value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
	}

	private String upper(String value) {
		return isBlank(value) ? null : value.toUpperCase(Locale.ROOT);
	}

	private String clean(String value) {
		if (value == null || value.isBlank() || "-".equals(value.trim()) || "null".equalsIgnoreCase(value.trim())) return null;
		return value.trim();
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
