package com.selene.onestepflight.service;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
	static final String IMAGE_DATA_SOURCE = "NUTRIENT_DWS_IMAGE_UNDERSTAND";
	private static final List<String> SEGMENT_FIELD_NAMES = List.of(
			"flight_number", "departure_date", "departure_airport", "arrival_airport",
			"scheduled_departure", "scheduled_arrival", "boarding_time", "terminal", "gate", "seat");
	private static final List<String> IMAGE_SEGMENT_FIELD_NAMES = List.of(
			"flight_number", "departure_date", "departure_month_day", "departure_date_precision",
			"departure_airport", "arrival_airport", "scheduled_departure", "scheduled_arrival",
			"boarding_time", "terminal", "gate", "seat");
	private static final Set<String> REQUIRED_IDENTITY_FIELDS = Set.of(
			"flight_number", "departure_date", "departure_airport", "arrival_airport");

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final String apiKeyOverride;

	@Autowired
	public NutrientBoardingPassDocumentProcessor(ObjectMapper objectMapper) {
		this(objectMapper, productionRestClient(), null);
	}

	NutrientBoardingPassDocumentProcessor(ObjectMapper objectMapper, RestClient restClient,
			String apiKeyOverride) {
		this.objectMapper = objectMapper;
		this.restClient = restClient;
		this.apiKeyOverride = apiKeyOverride;
	}

	private static RestClient productionRestClient() {
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
		return RestClient.builder()
				.requestFactory(new BufferingClientHttpRequestFactory(requestFactory))
				.build();
	}

	@Override
	public BoardingPassExtractionResponse extract(byte[] documentBytes, String filename, String contentType) {
		String apiKey = apiKeyOverride == null ? System.getenv("NUTRIENT_API_KEY") : apiKeyOverride;
		if (apiKey == null || apiKey.isBlank()) {
			throw new ExternalServiceNotConfiguredException("NUTRIENT");
		}
		apiKey = apiKey.trim();

		JsonNode response;
		try {
			response = requestExtraction(documentBytes, filename, contentType, apiKey,
					isImageContentType(contentType));
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

		return isImageContentType(contentType) ? toImageResponse(response) : toResponse(response);
	}

	private JsonNode requestExtraction(byte[] documentBytes, String filename, String contentType, String apiKey,
			boolean image)
			throws JacksonException {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", filePart(documentBytes, filename, contentType));
		body.add("instructions", image ? imageInstructionsPart() : instructionsPart());
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

	private boolean isImageContentType(String contentType) {
		return MediaType.IMAGE_JPEG_VALUE.equalsIgnoreCase(contentType)
				|| MediaType.IMAGE_PNG_VALUE.equalsIgnoreCase(contentType);
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

	private HttpEntity<String> imageInstructionsPart() throws JacksonException {
		Map<String, Object> instructions = new LinkedHashMap<>();
		instructions.put("mode", "understand");
		instructions.put("schema", imageDocumentSchema());
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
		schema.put("description", "Extract every distinct flight leg into segments. Do not silently choose the first leg and do not guess missing values. Never extract or return passenger names, PNRs, booking references, ticket numbers, passport data, or barcode contents.");
		schema.put("properties", properties);
		// Non-flight and unclear files return UNKNOWN with an empty segments array;
		// no flight identity is required or invented.
		schema.put("required", List.of("document_type", "segments"));
		return schema;
	}

	Map<String, Object> imageDocumentSchema() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("document_type", enumProperty(
				"Classify only the visible image. Use UNKNOWN for a non-flight image or when the type is unclear."));
		properties.put("segments", imageSegmentArrayProperty());
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("description", "Extract visible flight legs in top-to-bottom order. Bind each flight number, route, date, and time to its own flight block. A clearly scoped itinerary-header date may apply to following legs until a new date or journey section. Never copy fields between legs or infer a year. If only month and day are visible, omit departure_date and use departure_month_day. Omit uncertain values and sensitive passenger or booking data.");
		schema.put("properties", properties);
		schema.put("required", List.of("document_type", "segments"));
		return schema;
	}

	private Map<String, Object> imageSegmentArrayProperty() {
		Map<String, Object> segmentProperties = new LinkedHashMap<>();
		segmentProperties.put("flight_number", stringProperty("The flight designator actually printed inside this visual segment. Omit it when not explicitly visible, and never infer it from an airline name or copy it from another segment."));
		segmentProperties.put("departure_date", stringProperty("Local departure date in ISO YYYY-MM-DD format. Return it only when this visual segment explicitly shows a four-digit year; otherwise omit it."));
		segmentProperties.put("departure_month_day", stringProperty("Local departure month and day in MM-DD format when month and day are visible. This does not authorize inferring a year."));
		segmentProperties.put("departure_date_precision", stringEnumProperty(
				List.of("FULL_DATE_VISIBLE", "MONTH_DAY_ONLY", "UNKNOWN"),
				"Use FULL_DATE_VISIBLE only when a four-digit departure year is explicitly visible in this visual segment; use MONTH_DAY_ONLY when only month and day are visible; otherwise use UNKNOWN."));
		segmentProperties.put("departure_airport", stringProperty("Three-letter IATA departure airport code printed in this same visual segment."));
		segmentProperties.put("arrival_airport", stringProperty("Three-letter IATA arrival airport code printed in this same visual segment."));
		segmentProperties.put("scheduled_departure", stringProperty("Scheduled local departure date and time in ISO format only when complete without guessing a year. Never use an arrival value."));
		segmentProperties.put("scheduled_arrival", stringProperty("Scheduled local arrival date and time in ISO format only when complete without guessing a year."));
		segmentProperties.put("boarding_time", stringProperty("Local boarding time in HH:mm format when printed in this same visual segment."));
		segmentProperties.put("terminal", stringProperty("Departure terminal exactly as printed in this same visual segment, without guessing."));
		segmentProperties.put("gate", stringProperty("Departure gate exactly as printed in this same visual segment, without guessing."));
		segmentProperties.put("seat", stringProperty("Seat number exactly as printed in this same visual segment, when present."));
		Map<String, Object> items = new LinkedHashMap<>();
		items.put("type", "object");
		items.put("properties", segmentProperties);
		Map<String, Object> array = new LinkedHashMap<>();
		array.put("type", "array");
		array.put("description", "Every visually independent flight segment in original top-to-bottom order. Do not merge, reorder, complete, or cross-bind fields between segments.");
		array.put("items", items);
		return array;
	}

	private Map<String, Object> segmentArrayProperty() {
		Map<String, Object> segmentProperties = new LinkedHashMap<>();
		segmentProperties.put("flight_number", stringProperty("Operating or marketed flight designator, for example BA117. Never return a booking reference, PNR, passenger name, ticket number, or barcode data."));
		segmentProperties.put("departure_date", stringProperty("Local departure date in ISO YYYY-MM-DD format."));
		segmentProperties.put("departure_airport", stringProperty("Three-letter IATA departure airport code."));
		segmentProperties.put("arrival_airport", stringProperty("Three-letter IATA arrival airport code."));
		segmentProperties.put("scheduled_departure", stringProperty("Scheduled local departure date and time in ISO YYYY-MM-DDTHH:mm format when shown."));
		segmentProperties.put("scheduled_arrival", stringProperty("Scheduled local arrival date and time in ISO YYYY-MM-DDTHH:mm format when shown."));
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

	private Map<String, Object> stringEnumProperty(List<String> values, String description) {
		return Map.of("type", "string", "enum", values, "description", description);
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

		double documentTypeConfidence = fields.get("document_type").confidence();
		if (documentType == DocumentType.UNKNOWN && !needsReview.contains("document_type")) {
			needsReview.add(0, "document_type");
		}
		boolean boardingPassDetected = segments.size() == 1 && hasUsableIdentity(segments.get(0))
				&& documentType.impliesCheckInCompleted(documentTypeConfidence);
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
				boardingPassDetected,
				documentType.requiresConfirmation(documentTypeConfidence),
				fields,
				segments,
				List.copyOf(needsReview),
				"NUTRIENT_DWS_DATA_EXTRACTION",
				Instant.now());
	}

	BoardingPassExtractionResponse toImageResponse(JsonNode response) {
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
		List<DocumentFlightSegment> segments = parseImageSegments(data, metadata, needsReview);
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
				IMAGE_DATA_SOURCE,
				Instant.now());
	}

	private List<DocumentFlightSegment> parseImageSegments(JsonNode data, JsonNode metadata,
			List<String> needsReview) {
		List<DocumentFlightSegment> segments = new ArrayList<>();
		JsonNode segmentData = data.path("segments");
		if (!segmentData.isArray()) return List.of();
		for (int index = 0; index < segmentData.size(); index++) {
			segments.add(parseImageSegment(segmentData.path(index), segmentMetadata(metadata, index),
					index, needsReview));
		}

		Map<String, Integer> flightNumberCounts = new LinkedHashMap<>();
		for (DocumentFlightSegment segment : segments) {
			if (segment.flightNumber() != null) {
				flightNumberCounts.merge(segment.flightNumber(), 1, Integer::sum);
			}
		}
		for (int index = 0; index < segments.size(); index++) {
			DocumentFlightSegment segment = segments.get(index);
			if (segment.flightNumber() == null
					|| flightNumberCounts.getOrDefault(segment.flightNumber(), 0) < 2) continue;
			segments.set(index, withFlightNumberConfirmation(segment));
			addReviewField(needsReview, "segments[" + index + "].flight_number");
		}
		return List.copyOf(segments);
	}

	private DocumentFlightSegment parseImageSegment(JsonNode data, JsonNode metadata, int index,
			List<String> needsReview) {
		Map<String, ExtractedField> rawFields = new LinkedHashMap<>();
		for (String fieldName : IMAGE_SEGMENT_FIELD_NAMES) {
			rawFields.put(fieldName, extractedField(data, metadata, fieldName,
					"segments[" + index + "]." + fieldName));
		}

		String precision = normalizeDatePrecision(rawFields.get("departure_date_precision").value());
		LocalDate fullDate = "FULL_DATE_VISIBLE".equals(precision)
				? parseDate(rawFields.get("departure_date").value()) : null;
		String monthDay = validMonthDay(rawFields.get("departure_month_day").value());
		Map<String, ExtractedField> segmentFields = new LinkedHashMap<>();
		for (String fieldName : IMAGE_SEGMENT_FIELD_NAMES) {
			ExtractedField rawField = rawFields.get(fieldName);
			String value = switch (fieldName) {
				case "departure_date" -> fullDate == null ? null : fullDate.toString();
				case "departure_month_day" -> monthDay;
				case "departure_date_precision" -> precision;
				case "scheduled_departure", "scheduled_arrival" ->
						fullDate == null ? null : validDateTime(rawField.value());
				default -> sanitizeField(fieldName, rawField).value();
			};
			segmentFields.put(fieldName, withValue(rawField, value));
		}

		ExtractedField flightNumberField = segmentFields.get("flight_number");
		String flightNumber = flightNumberField.value();
		DocumentFlightSegment.FlightNumberConfidence flightConfidence =
				flightNumberConfidence(flightNumberField.confidence());
		if (flightNumber == null || flightConfidence == DocumentFlightSegment.FlightNumberConfidence.LOW) {
			addReviewField(needsReview, "segments[" + index + "].flight_number");
		}
		if (fullDate == null) {
			addReviewField(needsReview, "segments[" + index + "].departure_date");
		}
		for (String fieldName : List.of("departure_airport", "arrival_airport", "scheduled_departure",
				"scheduled_arrival", "boarding_time", "terminal", "gate", "seat")) {
			ExtractedField field = segmentFields.get(fieldName);
			if (field.value() != null && field.confidence() < 0.65d) {
				addReviewField(needsReview, "segments[" + index + "]." + fieldName);
			}
		}

		ExtractedField identity = segmentFields.get("flight_number");
		return new DocumentFlightSegment(
				flightNumber,
				fullDate,
				monthDay,
				normalizeAirport(segmentFields.get("departure_airport").value()),
				normalizeAirport(segmentFields.get("arrival_airport").value()),
				segmentFields.get("scheduled_departure").value(),
				segmentFields.get("scheduled_arrival").value(),
				segmentFields.get("boarding_time").value(),
				segmentFields.get("terminal").value(),
				segmentFields.get("gate").value(),
				segmentFields.get("seat").value(),
				segmentConfidence(segmentFields),
				identity.page(),
				identity.boundingBox(),
				segmentFields,
				flightConfidence,
				flightNumber != null
						&& flightConfidence == DocumentFlightSegment.FlightNumberConfidence.LOW,
				false,
				fullDate == null ? DocumentFlightSegment.DateSource.MISSING
						: DocumentFlightSegment.DateSource.EXPLICIT_FULL_DATE);
	}

	private DocumentFlightSegment withFlightNumberConfirmation(DocumentFlightSegment segment) {
		Map<String, ExtractedField> imageFields = new LinkedHashMap<>(segment.fields());
		// The record's public whitelist intentionally removes this image-only control
		// field. Reattach it just for construction so the image confirmation flag is
		// not mistaken for a legacy PDF advisory.
		imageFields.put("departure_date_precision",
				new ExtractedField(null, 0.0d, null, null, null));
		return new DocumentFlightSegment(segment.flightNumber(), segment.departureDate(),
				segment.departureMonthDay(), segment.departureAirport(), segment.arrivalAirport(),
				segment.scheduledDeparture(), segment.scheduledArrival(), segment.boardingTime(),
				segment.terminal(), segment.gate(), segment.seat(), segment.confidence(),
				segment.page(), segment.boundingBox(), imageFields, segment.flightNumberConfidence(),
				true, segment.flightNumberConfirmedByUser(), segment.dateSource());
	}

	private ExtractedField withValue(ExtractedField field, String value) {
		return new ExtractedField(value, field.confidence(), field.page(), field.boundingBox(),
				field.sourceLabel());
	}

	private void addReviewField(List<String> needsReview, String field) {
		if (!needsReview.contains(field)) needsReview.add(field);
	}

	private DocumentFlightSegment.FlightNumberConfidence flightNumberConfidence(double confidence) {
		if (!Double.isFinite(confidence) || confidence <= 0.0d) {
			return DocumentFlightSegment.FlightNumberConfidence.LOW;
		}
		if (confidence < 0.60d) {
			return DocumentFlightSegment.FlightNumberConfidence.LOW;
		}
		return confidence < 0.85d ? DocumentFlightSegment.FlightNumberConfidence.MEDIUM
				: DocumentFlightSegment.FlightNumberConfidence.HIGH;
	}

	private String normalizeDatePrecision(String value) {
		if (value == null) return "UNKNOWN";
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		return List.of("FULL_DATE_VISIBLE", "MONTH_DAY_ONLY", "UNKNOWN").contains(normalized)
				? normalized : "UNKNOWN";
	}

	private String validMonthDay(String value) {
		if (value == null || !value.matches("\\d{2}-\\d{2}")) return null;
		try {
			LocalDate.of(2000, Integer.parseInt(value.substring(0, 2)),
					Integer.parseInt(value.substring(3, 5)));
			return value;
		} catch (RuntimeException exception) {
			return null;
		}
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
			if ((REQUIRED_IDENTITY_FIELDS.contains(fieldName) && field.value() == null)
					|| (field.value() != null && field.confidence() < threshold)) {
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
			case "departure_date" -> {
				LocalDate date = parseDate(field.value());
				yield date == null ? null : date.toString();
			}
			case "departure_airport", "arrival_airport" -> normalizeAirport(field.value());
			case "scheduled_departure", "scheduled_arrival" -> validDateTime(field.value());
			case "boarding_time" -> validBoardingTime(field.value());
			case "terminal", "gate", "seat" -> safeShortValue(field.value());
			default -> null;
		};
		return new ExtractedField(value, field.confidence(), field.page(), field.boundingBox(), field.sourceLabel());
	}

	private boolean hasUsableIdentity(DocumentFlightSegment segment) {
		return segment.flightNumber() != null && segment.departureDate() != null
				&& segment.departureAirport() != null && segment.arrivalAirport() != null;
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
		try {
			return value == null ? null : LocalDate.parse(value);
		} catch (DateTimeParseException exception) {
			return null;
		}
	}
}
