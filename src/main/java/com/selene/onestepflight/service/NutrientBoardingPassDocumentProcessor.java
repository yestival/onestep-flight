package com.selene.onestepflight.service;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;
import com.selene.onestepflight.dto.BoundingBox;
import com.selene.onestepflight.dto.DocumentType;
import com.selene.onestepflight.dto.DocumentFlightSegment;
import com.selene.onestepflight.dto.ExtractedField;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class NutrientBoardingPassDocumentProcessor implements BoardingPassDocumentProcessor {

	private static final Logger LOGGER = LoggerFactory.getLogger(NutrientBoardingPassDocumentProcessor.class);
	private static final String EXTRACT_ENDPOINT = "https://api.nutrient.io/extraction/extract";
	private static final List<String> SEGMENT_FIELD_NAMES = List.of(
			"flight_number", "departure_date", "departure_airport", "arrival_airport",
			"scheduled_departure", "scheduled_arrival", "boarding_time", "terminal", "gate", "seat");
	private static final Set<String> REQUIRED_IDENTITY_FIELDS = Set.of(
			"flight_number", "departure_date", "departure_airport", "arrival_airport");
	private static final Pattern FULL_CHINESE_DATE = Pattern.compile(
			"^(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?$");
	private static final Pattern CHINESE_MONTH_DAY = Pattern.compile(
			"^(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?$");
	private static final Pattern SLASH_MONTH_DAY = Pattern.compile("^(\\d{1,2})/(\\d{1,2})$");
	private static final Pattern SLASH_FULL_DATE = Pattern.compile("^(\\d{4})/(\\d{1,2})/(\\d{1,2})$");
	private static final List<DateTimeFormatter> ENGLISH_MONTH_DAY_FORMATTERS = List.of(
			englishMonthDayFormatter("MMM d"),
			englishMonthDayFormatter("MMMM d"),
			englishMonthDayFormatter("d MMM"),
			englishMonthDayFormatter("d MMMM"));

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	@Autowired
	public NutrientBoardingPassDocumentProcessor(ObjectMapper objectMapper) {
		this(objectMapper, Clock.systemUTC());
	}

	NutrientBoardingPassDocumentProcessor(ObjectMapper objectMapper, Clock clock) {
		HttpClient.Builder clientBuilder = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(8))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.version(HttpClient.Version.HTTP_1_1);
		ProxySelector proxySelector = ProxySelector.getDefault();
		if (proxySelector != null) clientBuilder.proxy(proxySelector);
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(clientBuilder.build());
		// Keep connection (8 s) plus response read within the 60-second extraction budget.
		requestFactory.setReadTimeout(Duration.ofSeconds(52));
		// Nutrient's extraction endpoint currently resets chunked multipart uploads.
		// Buffering is bounded by the controller's 10 MB upload limit and gives the
		// JDK client an exact Content-Length without changing TLS verification.
		this.restClient = RestClient.builder()
				.requestFactory(new BufferingClientHttpRequestFactory(requestFactory))
				.build();
		this.objectMapper = objectMapper;
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Override
	public BoardingPassExtractionResponse extract(byte[] documentBytes, String filename, String contentType) {
		String apiKey = System.getenv("NUTRIENT_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			throw new ExternalServiceNotConfiguredException("NUTRIENT");
		}
		apiKey = apiKey.trim();

		JsonNode response;
		try {
			response = requestExtraction(documentBytes, filename, contentType, apiKey);
		} catch (RestClientResponseException exception) {
			LOGGER.warn("provider=NUTRIENT stage=extraction httpStatus={} rootCauseType={} category=HTTP_REJECTED message={}",
					exception.getStatusCode().value(), exception.getClass().getName(),
					ProviderFailureDiagnostics.safeMessage(exception));
			throw new ExternalServiceUnavailableException("NUTRIENT_EXTRACTION_FAILED");
		} catch (RestClientException exception) {
			ProviderFailureDiagnostics.Failure failure = ProviderFailureDiagnostics.classify("NUTRIENT", exception);
			LOGGER.warn("provider=NUTRIENT stage=extraction httpStatus=none rootCauseType={} category={} message={}",
					failure.rootCauseType(), failure.category(), failure.safeMessage());
			throw new ExternalServiceUnavailableException("NUTRIENT_EXTRACTION_FAILED");
		} catch (JacksonException exception) {
			LOGGER.warn("provider=NUTRIENT stage=request-build httpStatus=none rootCauseType={} category=SERIALIZATION_ERROR message={}",
					exception.getClass().getName(), ProviderFailureDiagnostics.safeMessage(exception));
			throw new ExternalServiceUnavailableException("NUTRIENT_EXTRACTION_FAILED");
		}

		return toResponse(response);
	}

	private JsonNode requestExtraction(byte[] documentBytes, String filename, String contentType, String apiKey)
			throws JacksonException {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", filePart(documentBytes, filename, contentType));
		body.add("instructions", instructionsPart());
		ResponseEntity<JsonNode> response = restClient.post()
				.uri(EXTRACT_ENDPOINT)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(body)
				.retrieve()
				.toEntity(JsonNode.class);
		LOGGER.info("provider=NUTRIENT stage=extraction httpStatus={} contentType={}",
				response.getStatusCode().value(), response.getHeaders().getContentType());
		return response.getBody();
	}

	private HttpEntity<ByteArrayResource> filePart(byte[] bytes, String filename, String contentType) {
		ByteArrayResource resource = new ByteArrayResource(bytes) {
			@Override
			public String getFilename() {
				return filename;
			}
		};
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.parseMediaType(contentType));
		return new HttpEntity<>(resource, headers);
	}

	private HttpEntity<String> instructionsPart() throws JacksonException {
		Map<String, Object> instructions = new LinkedHashMap<>();
		instructions.put("mode", "understand");
		instructions.put("schema", documentSchema());
		instructions.put("citationsEnabled", true);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return new HttpEntity<>(objectMapper.writeValueAsString(instructions), headers);
	}

	Map<String, Object> documentSchema() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("document_type", enumProperty(
				"Classify the visible document only. Use UNKNOWN for a non-flight document or when the type is unclear."));
		properties.put("segments", segmentArrayProperty());
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("description", "Extract every distinct flight leg into segments in the same order as the document. Do not silently choose the first leg and do not guess missing values. A departure date visibly shared by a heading or table section may be copied unchanged only to the segments clearly governed by that heading or section; never carry it across a different group, page, or unrelated segment. Never extract or return passenger names, PNRs, booking references, ticket numbers, passport data, or barcode contents.");
		schema.put("properties", properties);
		// Non-flight and unclear files return UNKNOWN with an empty segments array;
		// no flight identity is required or invented.
		schema.put("required", List.of("document_type", "segments"));
		return schema;
	}

	private Map<String, Object> segmentArrayProperty() {
		Map<String, Object> segmentProperties = new LinkedHashMap<>();
		segmentProperties.put("flight_number", stringProperty("Operating or marketed flight designator, for example BA117. Never return a booking reference, PNR, passenger name, ticket number, or barcode data."));
		segmentProperties.put("departure_date", stringProperty("Copy the visible local departure date without inventing a year. Accepted visible forms are ISO YYYY-MM-DD, YYYY年M月D日, M月D日, MM/DD or M/D, Aug 4, and 4 Aug. A date printed once in a shared heading may be repeated only for the segments visibly governed by that heading."));
		segmentProperties.put("departure_airport", stringProperty("Three-letter IATA departure airport code."));
		segmentProperties.put("arrival_airport", stringProperty("Three-letter IATA arrival airport code."));
		segmentProperties.put("scheduled_departure", stringProperty("Scheduled local departure date and time in ISO YYYY-MM-DDTHH:mm format when shown."));
		segmentProperties.put("scheduled_arrival", stringProperty("Scheduled local arrival date and time in ISO YYYY-MM-DDTHH:mm format when shown. If the document explicitly marks arrival as next day or +1, preserve that meaning by using the next calendar date here; never change departure_date and never infer a rollover without an explicit marker."));
		segmentProperties.put("boarding_time", stringProperty("Local boarding time in HH:mm format when shown."));
		segmentProperties.put("terminal", stringProperty("Departure terminal exactly as printed, without guessing."));
		segmentProperties.put("gate", stringProperty("Departure gate exactly as printed, without guessing."));
		segmentProperties.put("seat", stringProperty("Seat number exactly as printed, when present."));
		return Map.of(
				"type", "array",
				"description", "All flight legs visible in document order. Return one item for a boarding pass or single-leg itinerary.",
				"items", Map.of("type", "object", "properties", segmentProperties));
	}

	private Map<String, String> stringProperty(String description) {
		return Map.of("type", "string", "description", description);
	}

	private Map<String, Object> enumProperty(String description) {
		return Map.of(
				"type", "string",
				"enum", List.of("BOARDING_PASS", "ITINERARY", "E_TICKET", "BOOKING_CONFIRMATION", "UNKNOWN"),
				"description", description);
	}

	BoardingPassExtractionResponse toResponse(JsonNode response) {
		if (response == null || !response.path("output").path("data").isObject()) {
			throw new ExternalServiceUnavailableException("NUTRIENT_RESPONSE_INVALID");
		}

		JsonNode data = response.path("output").path("data");
		JsonNode metadata = response.path("output").path("metadata");
		Map<String, ExtractedField> fields = new LinkedHashMap<>();
		ExtractedField rawDocumentType = extractedField(data, metadata, "document_type");
		DocumentType documentType = DocumentType.fromProviderValue(rawDocumentType.value());
		fields.put("document_type", new ExtractedField(documentType.name(), rawDocumentType.confidence(),
				rawDocumentType.page(), rawDocumentType.boundingBox(), rawDocumentType.sourceLabel()));

		List<String> needsReview = new ArrayList<>();
		if (rawDocumentType.value() == null
				|| rawDocumentType.confidence() < DocumentType.HIGH_CONFIDENCE_THRESHOLD) {
			needsReview.add("document_type");
		}
		List<DocumentFlightSegment> segments = parseSegments(data, metadata, needsReview);
		if (segments.size() == 1) {
			fields.putAll(segments.get(0).fields());
			for (int index = 0; index < needsReview.size(); index++) {
				needsReview.set(index, needsReview.get(index).replace("segments[0].", ""));
			}
		}

		if (documentType == DocumentType.UNKNOWN && !needsReview.contains("document_type")) {
			needsReview.add(0, "document_type");
		}
		return new BoardingPassExtractionResponse(
				documentType,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				false,
				false,
				fields,
				segments,
				List.copyOf(needsReview),
				"NUTRIENT_DWS_DATA_EXTRACTION",
				Instant.now(clock));
	}

	private List<DocumentFlightSegment> parseSegments(JsonNode data, JsonNode metadata,
			List<String> needsReview) {
		List<DocumentFlightSegment> segments = new ArrayList<>();
		JsonNode segmentData = data.path("segments");
		if (segmentData.isArray()) {
			for (int index = 0; index < segmentData.size(); index++) {
				segments.add(parseSegment(segmentData.path(index), segmentMetadata(metadata, index),
						index, needsReview));
			}
		}
		if (segments.isEmpty() && hasLegacySegment(data)) {
			segments.add(parseSegment(data, metadata, 0, needsReview));
		}
		return List.copyOf(segments);
	}

	private DocumentFlightSegment parseSegment(JsonNode data, JsonNode metadata, int index,
			List<String> needsReview) {
		Map<String, ExtractedField> segmentFields = new LinkedHashMap<>();
		for (String fieldName : SEGMENT_FIELD_NAMES) {
			ExtractedField field = sanitizeField(fieldName, extractedField(data, metadata, fieldName,
					"segments[" + index + "]." + fieldName));
			segmentFields.put(fieldName, field);
			double threshold = "flight_number".equals(fieldName) || "departure_date".equals(fieldName)
					? 0.8d : 0.65d;
			boolean currentYearMonthDay = "departure_date".equals(fieldName)
					&& parseMonthDay(normalizeDateText(field.value())) != null;
			if ((REQUIRED_IDENTITY_FIELDS.contains(fieldName) && field.value() == null)
					|| (field.value() != null && field.confidence() < threshold && !currentYearMonthDay)) {
				needsReview.add("segments[" + index + "]." + fieldName);
			}
		}
		ExtractedField identity = segmentFields.get("flight_number");
		double confidence = segmentConfidence(segmentFields);
		return new DocumentFlightSegment(
				normalizeFlightNumber(segmentFields.get("flight_number").value()),
				parseDate(segmentFields.get("departure_date").value()),
				normalizeAirport(segmentFields.get("departure_airport").value()),
				normalizeAirport(segmentFields.get("arrival_airport").value()),
				segmentFields.get("scheduled_departure").value(),
				segmentFields.get("scheduled_arrival").value(),
				segmentFields.get("boarding_time").value(),
				segmentFields.get("terminal").value(),
				segmentFields.get("gate").value(),
				segmentFields.get("seat").value(),
				confidence,
				identity.page(),
				identity.boundingBox(),
				segmentFields);
	}

	private ExtractedField sanitizeField(String name, ExtractedField field) {
		String value = switch (name) {
			case "flight_number" -> normalizeFlightNumber(field.value());
			// Keep the printed date text as evidence. The semantic LocalDate on the
			// segment is parsed separately and can safely supply the current year when
			// the document displays only an unambiguous month and day.
			case "departure_date" -> parseDate(field.value()) == null ? null : field.value().trim();
			case "departure_airport", "arrival_airport" -> normalizeAirport(field.value());
			case "scheduled_departure", "scheduled_arrival" -> validDateTime(field.value());
			case "boarding_time" -> validBoardingTime(field.value());
			case "terminal", "gate", "seat" -> safeShortValue(field.value());
			default -> null;
		};
		return new ExtractedField(value, field.confidence(), field.page(), field.boundingBox(), field.sourceLabel());
	}

	private JsonNode segmentMetadata(JsonNode metadata, int index) {
		JsonNode segments = metadata.path("segments");
		if (segments.isArray()) return segments.path(index);
		if (segments.isObject() && !segments.path(Integer.toString(index)).isMissingNode()) {
			return segments.path(Integer.toString(index));
		}
		return metadata;
	}

	private boolean hasLegacySegment(JsonNode data) {
		return SEGMENT_FIELD_NAMES.stream().anyMatch(name -> valueOrNull(data.path(name)) != null);
	}

	private double segmentConfidence(Map<String, ExtractedField> fields) {
		return fields.entrySet().stream()
				.filter(entry -> List.of("flight_number", "departure_date", "departure_airport", "arrival_airport")
						.contains(entry.getKey()))
				.mapToDouble(entry -> entry.getValue().confidence())
				.filter(value -> value > 0.0d)
				.average().orElse(0.0d);
	}

	private ExtractedField extractedField(JsonNode data, JsonNode metadata, String name) {
		return extractedField(data, metadata, name, name);
	}

	private ExtractedField extractedField(JsonNode data, JsonNode metadata, String name, String sourcePath) {
		String value = valueOrNull(data.path(name));
		JsonNode fieldMetadata = metadata.path(name);
		double confidence = fieldMetadata.path("confidence").asDouble(0.0);
		Integer page = fieldMetadata.path("pageIndex").isNumber() ? fieldMetadata.path("pageIndex").asInt() : null;
		BoundingBox box = boundingBox(fieldMetadata.path("bbox"));
		return new ExtractedField(value, confidence, page, box, "Nutrient citation: " + sourcePath);
	}

	private BoundingBox boundingBox(JsonNode node) {
		if (!node.isObject()) return null;
		return new BoundingBox(node.path("x").asDouble(), node.path("y").asDouble(),
				node.path("width").asDouble(), node.path("height").asDouble());
	}

	private String valueOrNull(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) return null;
		String value = node.isValueNode() ? node.asText().trim() : null;
		return value == null || value.isBlank() ? null : value;
	}

	private String normalizeFlightNumber(String value) {
		if (value == null) return null;
		String normalized = value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
		return normalized.matches("[A-Z0-9]{2,3}[0-9]{1,4}[A-Z]?") ? normalized : null;
	}

	private String normalizeAirport(String value) {
		if (value == null) return null;
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		return normalized.matches("[A-Z]{3}") ? normalized : null;
	}

	private String validDateTime(String value) {
		if (value == null) return null;
		try {
			OffsetDateTime.parse(value);
			return value;
		} catch (DateTimeParseException ignored) {
			// Printed itineraries commonly omit the UTC offset.
		}
		try {
			LocalDateTime.parse(value);
			return value;
		} catch (DateTimeParseException ignored) {
			return null;
		}
	}

	private String validBoardingTime(String value) {
		if (value == null) return null;
		try {
			LocalTime.parse(value);
			return value;
		} catch (DateTimeParseException ignored) {
			return validDateTime(value);
		}
	}

	private String safeShortValue(String value) {
		if (value == null) return null;
		String cleaned = value.trim();
		return cleaned.length() <= 24 && cleaned.matches("[\\p{L}\\p{N} ./-]+") ? cleaned : null;
	}

	private LocalDate parseDate(String value) {
		if (value == null || value.isBlank()) return null;
		String normalized = normalizeDateText(value);
		try {
			return LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE);
		} catch (DateTimeParseException exception) {
			// Continue with the other explicit, unambiguous document formats.
		}

		Matcher fullChinese = FULL_CHINESE_DATE.matcher(normalized);
		if (fullChinese.matches()) {
			return localDate(fullChinese.group(1), fullChinese.group(2), fullChinese.group(3));
		}
		Matcher fullSlash = SLASH_FULL_DATE.matcher(normalized);
		if (fullSlash.matches()) {
			return localDate(fullSlash.group(1), fullSlash.group(2), fullSlash.group(3));
		}

		MonthDay monthDay = parseMonthDay(normalized);
		if (monthDay == null) return null;
		try {
			return monthDay.atYear(LocalDate.now(clock).getYear());
		} catch (DateTimeException exception) {
			// For example, February 29 without a year in a non-leap current year.
			return null;
		}
	}

	private String normalizeDateText(String value) {
		return value == null ? "" : value.trim().replaceAll("\\s+", " ");
	}

	private MonthDay parseMonthDay(String normalized) {
		if (normalized == null || normalized.isBlank()) return null;
		MonthDay monthDay = null;
		Matcher chinese = CHINESE_MONTH_DAY.matcher(normalized);
		if (chinese.matches()) {
			monthDay = monthDay(chinese.group(1), chinese.group(2));
		}
		if (monthDay == null) {
			Matcher slash = SLASH_MONTH_DAY.matcher(normalized);
			if (slash.matches()) monthDay = monthDay(slash.group(1), slash.group(2));
		}
		if (monthDay == null) {
			for (DateTimeFormatter formatter : ENGLISH_MONTH_DAY_FORMATTERS) {
				try {
					monthDay = MonthDay.parse(normalized, formatter);
					break;
				} catch (DateTimeParseException ignored) {
					// Try the next exact English month/day order.
				}
			}
		}
		return monthDay;
	}

	private static DateTimeFormatter englishMonthDayFormatter(String pattern) {
		return new DateTimeFormatterBuilder()
				.parseCaseInsensitive()
				.appendPattern(pattern)
				.toFormatter(Locale.ENGLISH);
	}

	private LocalDate localDate(String year, String month, String day) {
		try {
			return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
		} catch (DateTimeException | NumberFormatException exception) {
			return null;
		}
	}

	private MonthDay monthDay(String month, String day) {
		try {
			return MonthDay.of(Integer.parseInt(month), Integer.parseInt(day));
		} catch (DateTimeException | NumberFormatException exception) {
			return null;
		}
	}
}
