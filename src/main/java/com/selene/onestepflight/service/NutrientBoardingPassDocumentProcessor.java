package com.selene.onestepflight.service;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.text.Normalizer;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;
import com.selene.onestepflight.dto.BoundingBox;
import com.selene.onestepflight.dto.DocumentFlightSegment;
import com.selene.onestepflight.dto.DocumentFlightSegment.DateSource;
import com.selene.onestepflight.dto.DocumentFlightSegment.FlightNumberConfidence;
import com.selene.onestepflight.dto.DocumentType;
import com.selene.onestepflight.dto.ExtractedField;
import com.selene.onestepflight.service.airline.AirlineRegistry;
import com.selene.onestepflight.service.airline.AirlineResolution;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class NutrientBoardingPassDocumentProcessor implements BoardingPassDocumentProcessor {

	private static final Logger LOGGER = LoggerFactory.getLogger(NutrientBoardingPassDocumentProcessor.class);
	private static final String EXTRACT_ENDPOINT = "https://api.nutrient.io/extraction/extract";
	private static final String PARSE_ENDPOINT = "https://api.nutrient.io/extraction/parse";
	private static final String DATA_SOURCE = "NUTRIENT_DWS_DATA_EXTRACTION";
	private static final String UNDERSTAND_STAGE = "understand";
	private static final String STRUCTURE_STAGE = "structure";

	private static final Pattern FLIGHT_NUMBER_PATTERN = Pattern.compile(
			"^(?:[A-Z][A-Z0-9]|[0-9][A-Z]|[A-Z][A-Z0-9]{2}|[0-9][A-Z][A-Z0-9]|[0-9]{2}[A-Z])\\d{1,4}[A-Z]?$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern FLIGHT_NUMBER_IN_TEXT = Pattern.compile(
			"(?<![A-Z0-9])((?:[A-Z][A-Z0-9]|[0-9][A-Z]|[A-Z][A-Z0-9]{2}|[0-9][A-Z][A-Z0-9]|[0-9]{2}[A-Z])\\s*[- ]?\\s*\\d{1,4}[A-Z]?)(?![A-Z0-9])",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern FLIGHT_PREFIX_FRAGMENT = Pattern.compile("^(?=.*[A-Z])[A-Z0-9]{2,3}$");
	private static final Pattern FLIGHT_SUFFIX_FRAGMENT = Pattern.compile("^\\d{1,4}[A-Z]?$" );
	private static final Pattern FLIGHT_NUMERIC_SUFFIX = Pattern.compile("(\\d{1,4}[A-Z]?)$");
	private static final Pattern IATA_IN_TEXT = Pattern.compile("(?<![A-Za-z0-9])([A-Z]{3})(?![A-Za-z0-9])");
	private static final Pattern TIME_IN_TEXT = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):[0-5]\\d(?!\\d)");
	private static final Pattern TERMINAL_IN_TEXT = Pattern.compile(
			"(?i)(?:航站楼|TERMINAL|\\bT)\\s*([0-9][A-Z]?)\\b");
	private static final Pattern DATE_IN_TEXT = Pattern.compile(
			"(?i)(?:\\d{4}-\\d{1,2}-\\d{1,2}|\\d{4}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日?|\\d{4}/\\d{1,2}/\\d{1,2}|\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日?|\\d{1,2}[-/]\\d{1,2}|[A-Za-z]{3,10}\\s+\\d{1,2}(?:,\\s*\\d{4})?|\\d{1,2}\\s+[A-Za-z]{3,10}(?:\\s+\\d{4})?)");
	private static final Pattern FULL_CHINESE_DATE = Pattern.compile(
			"^(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?$");
	private static final Pattern MONTH_DAY_CHINESE = Pattern.compile(
			"^(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?$");
	private static final Pattern SLASH_FULL_DATE = Pattern.compile("^(\\d{4})/(\\d{1,2})/(\\d{1,2})$");
	private static final Pattern SLASH_MONTH_DAY = Pattern.compile("^(\\d{1,2})/(\\d{1,2})$");
	private static final Pattern NORMALIZED_MONTH_DAY = Pattern.compile("^(\\d{1,2})-(\\d{1,2})$");
	private static final Pattern ENGLISH_MONTH_FIRST = Pattern.compile(
			"(?i)^([A-Za-z]+)\\s+(\\d{1,2})(?:,\\s*(\\d{4}))?$");
	private static final Pattern ENGLISH_DAY_FIRST = Pattern.compile(
			"(?i)^(\\d{1,2})\\s+([A-Za-z]+)(?:\\s+(\\d{4}))?$");
	private static final Pattern SEGMENT_REVIEW_PATTERN = Pattern.compile(
			"^segments\\[(\\d+)]\\.(.+)$");

	private static final List<String> SEGMENT_FIELD_NAMES = List.of(
			"flight_number", "departure_airport", "arrival_airport", "departure_date",
			"scheduled_departure", "scheduled_arrival", "departure_terminal", "arrival_terminal",
			"boarding_time", "terminal", "gate", "seat");
	private static final Set<String> REQUIRED_SEGMENT_FIELDS = Set.of(
			"flight_number", "departure_airport", "arrival_airport", "departure_date");
	private static final Set<String> NON_AIRPORT_TOKENS = Set.of(
			"AIR", "API", "APP", "BAG", "BAR", "DOC", "GMT", "IMG", "JPG", "KLM", "OCR",
			"PDF", "PNG", "PNR", "TKT", "UTC", "THE", "AND", "FROM", "TOO", "VIA");
	private static final Set<String> SENSITIVE_CONTEXT_WORDS = Set.of(
			"PNR", "BOOKING", "REFERENCE", "TICKET", "PASSPORT", "BARCODE", "ORDER",
			"预订", "订单", "票号", "护照", "乘客", "旅客");
	private static final Map<String, Integer> ENGLISH_MONTHS = Map.ofEntries(
			Map.entry("jan", 1), Map.entry("january", 1), Map.entry("feb", 2), Map.entry("february", 2),
			Map.entry("mar", 3), Map.entry("march", 3), Map.entry("apr", 4), Map.entry("april", 4),
			Map.entry("may", 5), Map.entry("jun", 6), Map.entry("june", 6), Map.entry("jul", 7),
			Map.entry("july", 7), Map.entry("aug", 8), Map.entry("august", 8), Map.entry("sep", 9),
			Map.entry("september", 9), Map.entry("oct", 10), Map.entry("october", 10),
			Map.entry("nov", 11), Map.entry("november", 11), Map.entry("dec", 12),
			Map.entry("december", 12), Map.entry("ene", 1), Map.entry("enero", 1),
			Map.entry("febrero", 2), Map.entry("marzo", 3), Map.entry("abr", 4),
			Map.entry("abril", 4), Map.entry("mayo", 5), Map.entry("junio", 6),
			Map.entry("julio", 7), Map.entry("ago", 8), Map.entry("agosto", 8),
			Map.entry("set", 9), Map.entry("sept", 9), Map.entry("septiembre", 9),
			Map.entry("octubre", 10), Map.entry("noviembre", 11), Map.entry("dic", 12),
			Map.entry("diciembre", 12));

	private static final Comparator<EvidenceBlock> EVIDENCE_ORDER = Comparator
			.comparingInt(EvidenceBlock::pageIndex)
			.thenComparingDouble(EvidenceBlock::y)
			.thenComparingDouble(EvidenceBlock::x)
			.thenComparingInt(EvidenceBlock::documentOrder);
	private static final Comparator<PositionedToken<?>> TOKEN_ORDER = Comparator
			.comparingInt((PositionedToken<?> token) -> token.block().pageIndex())
			.thenComparingDouble(token -> token.block().y())
			.thenComparingDouble(token -> token.block().x())
			.thenComparingInt(token -> token.block().documentOrder())
			.thenComparingInt(PositionedToken::characterOffset);

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final ExtractionRequester extractionRequester;
	private final AirlineRegistry airlineRegistry;

	@FunctionalInterface
	interface ExtractionRequester {
		JsonNode request(byte[] documentBytes, String filename, String contentType,
				String stage, Map<String, Object> instructions) throws JacksonException;
	}

	@Autowired
	public NutrientBoardingPassDocumentProcessor(ObjectMapper objectMapper) {
		this(objectMapper, Clock.systemUTC());
	}

	NutrientBoardingPassDocumentProcessor(ObjectMapper objectMapper, Clock clock) {
		this(objectMapper, clock, null);
	}

	NutrientBoardingPassDocumentProcessor(ObjectMapper objectMapper, Clock clock,
			ExtractionRequester extractionRequester) {
		this(objectMapper, clock, extractionRequester, AirlineRegistry.loadDefault());
	}

	NutrientBoardingPassDocumentProcessor(ObjectMapper objectMapper, Clock clock,
			ExtractionRequester extractionRequester, AirlineRegistry airlineRegistry) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.airlineRegistry = Objects.requireNonNull(airlineRegistry, "airlineRegistry");
		if (extractionRequester != null) {
			this.restClient = null;
			this.extractionRequester = extractionRequester;
			return;
		}
		HttpClient.Builder clientBuilder = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(8))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.version(HttpClient.Version.HTTP_1_1);
		ProxySelector proxySelector = ProxySelector.getDefault();
		if (proxySelector != null) clientBuilder.proxy(proxySelector);
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(clientBuilder.build());
		requestFactory.setReadTimeout(Duration.ofSeconds(52));
		this.restClient = RestClient.builder()
				.requestFactory(new BufferingClientHttpRequestFactory(requestFactory))
				.build();
		this.extractionRequester = this::requestExtraction;
	}

	@Override
	public BoardingPassExtractionResponse extract(byte[] documentBytes, String filename, String contentType) {
		ProviderAttempt understandAttempt = requestSafely(documentBytes, filename, contentType,
				UNDERSTAND_STAGE, understandInstructions(contentType));
		ParsedPass understand = parseUnderstandSafely(understandAttempt);

		// Structure is unconditional. Understand never decides whether this file
		// receives its one spatial pass.
		ProviderAttempt structureAttempt = requestSafely(documentBytes, filename, contentType,
				STRUCTURE_STAGE, structureInstructions());
		SpatialPass structure = parseSpatialSafely(structureAttempt);

		if (understand == null && structure == null) {
			throwBothFailed(understandAttempt.failure(), structureAttempt.failure());
		}
		BoardingPassExtractionResponse base = understand == null ? emptyResponse() : understand.response();
		List<SegmentCandidate> understandCandidates = understand == null ? List.of() : understand.candidates();
		List<SegmentCandidate> structureCandidates = structure == null ? List.of() : structure.candidates();
		MergeOutcome merged = mergeCandidates(understandCandidates, structureCandidates);
		BoardingPassExtractionResponse result = auditedResponse(base, merged, structure != null, structure);
		logOutcome(result, understand != null, structure != null, structure, merged);
		return result;
	}

	private ProviderAttempt requestSafely(byte[] documentBytes, String filename, String contentType,
			String stage, Map<String, Object> instructions) {
		try {
			return new ProviderAttempt(extractionRequester.request(documentBytes, filename, contentType,
					stage, instructions), null);
		} catch (ExternalServiceNotConfiguredException exception) {
			logStageFailure(stage, exception, null);
			return new ProviderAttempt(null, exception);
		} catch (RestClientResponseException exception) {
			logStageFailure(stage, exception, exception.getStatusCode().value());
			return new ProviderAttempt(null, new ExternalServiceUnavailableException("NUTRIENT_EXTRACTION_FAILED"));
		} catch (RestClientException | ExternalServiceUnavailableException exception) {
			logStageFailure(stage, exception, null);
			return new ProviderAttempt(null, new ExternalServiceUnavailableException("NUTRIENT_EXTRACTION_FAILED"));
		} catch (JacksonException exception) {
			logStageFailure(stage, exception, null);
			return new ProviderAttempt(null, new ExternalServiceUnavailableException("NUTRIENT_EXTRACTION_FAILED"));
		}
	}

	private ParsedPass parseUnderstandSafely(ProviderAttempt attempt) {
		if (attempt.response() == null) return null;
		try {
			return parsePass(attempt.response());
		} catch (RuntimeException exception) {
			logStageFailure(UNDERSTAND_STAGE + "-parse", exception, null);
			return null;
		}
	}

	private SpatialPass parseSpatialSafely(ProviderAttempt attempt) {
		if (attempt.response() == null) return null;
		try {
			return parseSpatial(attempt.response());
		} catch (RuntimeException exception) {
			logStageFailure(STRUCTURE_STAGE + "-parse", exception, null);
			return null;
		}
	}

	private void throwBothFailed(RuntimeException understandFailure, RuntimeException structureFailure) {
		if (understandFailure instanceof ExternalServiceNotConfiguredException
				|| structureFailure instanceof ExternalServiceNotConfiguredException) {
			throw new ExternalServiceNotConfiguredException("NUTRIENT");
		}
		throw new ExternalServiceUnavailableException("NUTRIENT_EXTRACTION_FAILED");
	}

	private void logStageFailure(String stage, Exception exception, Integer httpStatus) {
		LOGGER.warn("provider=NUTRIENT stage={} outcome=FAILED httpStatus={} errorType={}",
				stage, httpStatus == null ? "none" : httpStatus, exception.getClass().getSimpleName());
	}

	private JsonNode requestExtraction(byte[] documentBytes, String filename, String contentType,
			String stage, Map<String, Object> instructions) throws JacksonException {
		String apiKey = System.getenv("NUTRIENT_API_KEY");
		if (apiKey == null || apiKey.isBlank()) throw new ExternalServiceNotConfiguredException("NUTRIENT");
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", filePart(documentBytes, filename, contentType));
		body.add("instructions", instructionsPart(instructions));
		String endpoint = STRUCTURE_STAGE.equals(stage) ? PARSE_ENDPOINT : EXTRACT_ENDPOINT;
		ResponseEntity<JsonNode> response = restClient.post()
				.uri(endpoint)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(body)
				.retrieve()
				.toEntity(JsonNode.class);
		LOGGER.info("provider=NUTRIENT stage={} httpStatus={} responseContentType={}", stage,
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

	private HttpEntity<String> instructionsPart(Map<String, Object> instructions) throws JacksonException {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return new HttpEntity<>(objectMapper.writeValueAsString(instructions), headers);
	}

	Map<String, Object> understandInstructions(String contentType) {
		Map<String, Object> instructions = new LinkedHashMap<>();
		instructions.put("mode", "understand");
		instructions.put("schema", documentSchema());
		instructions.put("includeCitations", true);
		instructions.put("strict", false);
		instructions.put("multimodal", MediaType.IMAGE_JPEG_VALUE.equalsIgnoreCase(contentType)
				|| MediaType.IMAGE_PNG_VALUE.equalsIgnoreCase(contentType));
		return instructions;
	}

	Map<String, Object> structureInstructions() {
		return Map.of("mode", "structure", "output", Map.of("format", "spatial"));
	}

	Map<String, Object> documentSchema() {
		Map<String, Object> segmentProperties = new LinkedHashMap<>();
		segmentProperties.put("flight_number", stringProperty(
				"Copy the distinct marketed flight number visibly printed for this flight segment."));
		segmentProperties.put("departure_airport", stringProperty(
				"Copy only the departure airport visibly shown for this same flight segment."));
		segmentProperties.put("arrival_airport", stringProperty(
				"Copy only the arrival airport visibly shown for this same flight segment."));
		segmentProperties.put("departure_date", stringProperty(
				"Return the departure date exactly as visible, including month and day without a year."));
		segmentProperties.put("scheduled_departure", stringProperty(
				"Copy the scheduled departure time or date-time exactly as visibly supported."));
		segmentProperties.put("scheduled_arrival", stringProperty(
				"Copy the scheduled arrival time or date-time exactly as visibly supported."));
		segmentProperties.put("departure_terminal", stringProperty(
				"Copy the departure terminal visibly shown for this same segment."));
		segmentProperties.put("arrival_terminal", stringProperty(
				"Copy the arrival terminal visibly shown for this same segment."));
		segmentProperties.put("boarding_time", stringProperty("Copy the visible boarding time when present."));
		segmentProperties.put("terminal", stringProperty(
				"Copy the visible departure terminal for compatibility when only one terminal field is present."));
		segmentProperties.put("gate", stringProperty("Copy the visible departure gate when present."));
		segmentProperties.put("seat", stringProperty("Copy the visible seat when present."));

		Map<String, Object> item = new LinkedHashMap<>();
		item.put("type", "object");
		item.put("properties", segmentProperties);
		Map<String, Object> segmentArray = new LinkedHashMap<>();
		segmentArray.put("type", "array");
		segmentArray.put("description", "Create one segment for every distinct marketed flight number visibly present in the document. Preserve the visual order of the segments. Bind each flight number only to the departure and arrival airports shown for that same flight segment. Do not replace an individual segment route with the overall itinerary origin and final destination. Return dates exactly as visible. Do not invent a year when the document only shows month and day. Keep partially extracted segments instead of deleting them.");
		segmentArray.put("items", item);

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("document_type", enumProperty());
		properties.put("segments", segmentArray);
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("description", "Extract only flight-document fields. Never return passenger names, passport numbers, ticket numbers, order numbers, booking references, or barcode contents.");
		schema.put("properties", properties);
		schema.put("required", List.of("document_type", "segments"));
		return schema;
	}

	private Map<String, String> stringProperty(String description) {
		return Map.of("type", "string", "description", description);
	}

	private Map<String, Object> enumProperty() {
		return Map.of("type", "string", "enum",
				List.of("BOARDING_PASS", "ITINERARY", "E_TICKET", "BOOKING_CONFIRMATION", "UNKNOWN"),
				"description", "Classify only the visible travel document; use UNKNOWN when unclear.");
	}

	BoardingPassExtractionResponse toResponse(JsonNode response) {
		return parsePass(response).response();
	}

	private ParsedPass parsePass(JsonNode response) {
		JsonNode data = response == null ? null : response.path("output").path("data");
		if (data == null || !data.isObject()) {
			throw new ExternalServiceUnavailableException("NUTRIENT_RESPONSE_INVALID");
		}
		JsonNode metadata = response.path("output").path("metadata");
		Map<String, ExtractedField> rootFields = new LinkedHashMap<>();
		ExtractedField rawDocumentType = extractedField(data, metadata, "document_type", "document_type");
		DocumentType documentType = DocumentType.fromProviderValue(rawDocumentType.value());
		rootFields.put("document_type", new ExtractedField(documentType.name(), rawDocumentType.rawValue(),
				rawDocumentType.confidence(), rawDocumentType.page(), rawDocumentType.boundingBox(),
				rawDocumentType.sourceLabel()));

		List<String> review = new ArrayList<>();
		if (rawDocumentType.value() == null
				|| rawDocumentType.confidence() < DocumentType.HIGH_CONFIDENCE_THRESHOLD
				|| documentType == DocumentType.UNKNOWN) review.add("document_type");
		List<DocumentFlightSegment> segments = new ArrayList<>();
		List<SegmentCandidate> candidates = new ArrayList<>();
		JsonNode segmentData = data.path("segments");
		if (segmentData.isArray()) {
			for (int index = 0; index < segmentData.size(); index++) {
				DocumentFlightSegment segment = parseUnderstandSegment(segmentData.path(index),
						segmentMetadata(metadata, index), index, review);
				segments.add(segment);
				candidates.add(new SegmentCandidate(segment, segment.flightNumber(), index,
						CandidateSource.UNDERSTAND, fieldSources(segment)));
			}
		}
		if (segments.isEmpty() && hasLegacySegment(data)) {
			DocumentFlightSegment segment = parseUnderstandSegment(data, metadata, 0, review);
			segments.add(segment);
			candidates.add(new SegmentCandidate(segment, segment.flightNumber(), 0,
					CandidateSource.UNDERSTAND, fieldSources(segment)));
		}
		if (segments.size() == 1) {
			rootFields.putAll(segments.get(0).fields());
			review.replaceAll(field -> field.replace("segments[0].", ""));
		}
		BoardingPassExtractionResponse parsed = new BoardingPassExtractionResponse(
				documentType, null, null, null, null, null, null, null, null, null, null, null,
				false, false, rootFields, List.copyOf(segments), List.copyOf(new LinkedHashSet<>(review)),
				DATA_SOURCE, Instant.now(clock));
		return new ParsedPass(parsed, List.copyOf(candidates));
	}

	private DocumentFlightSegment parseUnderstandSegment(JsonNode data, JsonNode metadata, int index,
			List<String> review) {
		Map<String, ExtractedField> fields = emptyFields();
		for (String name : SEGMENT_FIELD_NAMES) {
			fields.put(name, sanitizeField(name,
					extractedField(data, metadata, name, "segments[" + index + "]." + name)));
		}
		DateParts date = dateParts(fields.get("departure_date").rawValue());
		DateParts scheduledDate = datePartsFromDateTime(fields.get("scheduled_departure").value());
		DateParts combinedDate = combineDateEvidence(date, scheduledDate);
		if (combinedDate.source() == DateSource.CONFLICT) {
			ExtractedField visibleDate = fields.get("departure_date");
			fields.put("departure_date", new ExtractedField(null, visibleDate.rawValue(),
					visibleDate.confidence(), visibleDate.page(), visibleDate.boundingBox(),
					visibleDate.sourceLabel()));
			review.add("segments[" + index + "].conflict.departure_date");
			date = DateParts.CONFLICT;
		} else if (combinedDate.hasValue()) {
			date = combinedDate;
			ExtractedField visibleDate = fields.get("departure_date");
			if (!Objects.equals(visibleDate.value(), date.publicValue())) {
				fields.put("departure_date", new ExtractedField(date.publicValue(), visibleDate.rawValue(),
						visibleDate.confidence(), visibleDate.page(), visibleDate.boundingBox(),
						visibleDate.sourceLabel()));
			}
		}
		String terminal = firstNonBlank(fields.get("departure_terminal").value(), fields.get("terminal").value());
		ExtractedField identity = fields.get("flight_number");
		FlightAssessment flightAssessment = assessUnderstandFlight(fields.get("flight_number"));
		if (flightAssessment.confidence() == FlightNumberConfidence.LOW) {
			fields.put("flight_number", new ExtractedField(null, identity.rawValue(), identity.confidence(),
					identity.page(), identity.boundingBox(), identity.sourceLabel()));
		}
		DocumentFlightSegment segment = new DocumentFlightSegment(
				flightAssessment.flightNumber(), date.date(), date.monthDay(),
				fields.get("departure_airport").value(), fields.get("arrival_airport").value(),
				fields.get("scheduled_departure").value(), fields.get("scheduled_arrival").value(),
				fields.get("boarding_time").value(), terminal, fields.get("gate").value(),
				fields.get("seat").value(), segmentConfidence(fields), identity.page(),
				identity.boundingBox(), fields, flightAssessment.confidence(),
				flightAssessment.requiresConfirmation(), false, date.source());
		addMissingReviewFields(review, segment, index);
		for (String name : SEGMENT_FIELD_NAMES) {
			ExtractedField field = fields.get(name);
			if (field != null && field.value() != null && field.confidence() > 0.0d
					&& field.confidence() < confidenceThreshold(name)) review.add("segments[" + index + "]." + name);
		}
		return segment;
	}

	private DateParts combineDateEvidence(DateParts visible, DateParts scheduled) {
		if (visible == null || !visible.hasValue()) return scheduled == null ? DateParts.EMPTY : scheduled;
		if (scheduled == null || !scheduled.hasValue()) return visible;
		if (!Objects.equals(visible.monthDay(), scheduled.monthDay())) return DateParts.CONFLICT;
		if (Objects.equals(visible.date(), scheduled.date())) {
			return visible.source() == DateSource.EXPLICIT_FULL_DATE ? visible : scheduled;
		}
		if (visible.source() == DateSource.CURRENT_YEAR_FROM_MONTH_DAY
				&& scheduled.source() == DateSource.EXPLICIT_FULL_DATE) return scheduled;
		if (scheduled.source() == DateSource.CURRENT_YEAR_FROM_MONTH_DAY
				&& visible.source() == DateSource.EXPLICIT_FULL_DATE) return visible;
		return DateParts.CONFLICT;
	}

	SpatialPass parseSpatial(JsonNode response) {
		if (response == null || response.isMissingNode() || response.isNull()
				|| (!response.isArray() && !response.isObject())) {
			throw new ExternalServiceUnavailableException("NUTRIENT_RESPONSE_INVALID");
		}
		EvidenceNormalization normalization = normalizeEvidence(response);
		List<EvidenceBlock> blocks = normalization.blocks();
		List<PositionedToken<String>> flightTokens = flightTokens(blocks);
		List<PositionedToken<String>> airports = airportTokens(blocks);
		List<PositionedToken<DateParts>> dates = dateTokens(blocks);
		List<PositionedToken<String>> times = timeTokens(blocks);
		List<PositionedToken<String>> terminals = terminalTokens(blocks);
		List<SegmentAnchor> anchors = anchors(flightTokens, airports, dates, times, terminals).stream()
				.filter(anchor -> assessSpatialFlight(anchor).confidence() != FlightNumberConfidence.LOW)
				.toList();

		List<SegmentCandidate> candidates = new ArrayList<>();
		for (int index = 0; index < anchors.size(); index++) {
			SegmentAnchor anchor = anchors.get(index);
			SegmentAnchor previous = index == 0 ? null : anchors.get(index - 1);
			SegmentAnchor next = index + 1 < anchors.size() ? anchors.get(index + 1) : null;
			PositionedToken<String> departure = nearestPreviousAirport(airports, anchor.token(),
					previous == null ? null : previous.token(), anchors.get(0).token());
			PositionedToken<String> arrival = nearestNext(airports, anchor.token(),
					next == null ? null : next.token());
			TokenRoute horizontalRoute = horizontalRoute(airports, anchor.token(),
					previous == null ? null : previous.token(), next == null ? null : next.token(),
					anchors.get(0).token());
			if (horizontalRoute != null) {
				departure = horizontalRoute.departure();
				arrival = horizontalRoute.arrival();
			}
			PositionedToken<DateParts> date = departureDateForAnchor(dates, anchors, index);
			PositionedToken<String> scheduledDeparture = nearestPrevious(times, anchor.token(),
					previous == null ? null : previous.token());
			PositionedToken<String> scheduledArrival = nearestNext(times, anchor.token(),
					next == null ? null : next.token());
			PositionedToken<String> departureTerminal = nearestPrevious(terminals, anchor.token(),
					previous == null ? null : previous.token());
			PositionedToken<String> arrivalTerminal = nearestNext(terminals, anchor.token(),
					next == null ? null : next.token());

			Map<String, ExtractedField> fields = emptyFields();
			putSpatialField(fields, "flight_number", anchor.flightNumber(), anchor.token());
			putSpatialField(fields, "departure_airport", tokenValue(departure), departure);
			putSpatialField(fields, "arrival_airport", tokenValue(arrival), arrival);
			DateParts dateParts = date == null ? DateParts.EMPTY : date.value();
			putSpatialField(fields, "departure_date", dateParts.publicValue(), date);
			putSpatialField(fields, "scheduled_departure", tokenValue(scheduledDeparture), scheduledDeparture);
			putSpatialField(fields, "scheduled_arrival", tokenValue(scheduledArrival), scheduledArrival);
			putSpatialField(fields, "departure_terminal", tokenValue(departureTerminal), departureTerminal);
			putSpatialField(fields, "arrival_terminal", tokenValue(arrivalTerminal), arrivalTerminal);
			FlightAssessment flightAssessment = assessSpatialFlight(anchor);
			DocumentFlightSegment segment = new DocumentFlightSegment(
					anchor.flightNumber(), dateParts.date(), dateParts.monthDay(), tokenValue(departure),
					tokenValue(arrival), tokenValue(scheduledDeparture), tokenValue(scheduledArrival),
					null, tokenValue(departureTerminal), null, null, segmentConfidence(fields),
					anchor.token().block().pageIndex(), anchor.token().block().box(), fields,
					flightAssessment.confidence(), flightAssessment.requiresConfirmation(), false,
					dateParts.source());
			candidates.add(new SegmentCandidate(segment, anchor.flightNumber(), index,
					CandidateSource.STRUCTURE, fieldSources(segment)));
		}
		int pages = (int) blocks.stream().map(EvidenceBlock::pageIndex).distinct().count();
		int duplicateFlightEvidence = Math.max(0, flightTokens.size() - anchors.size());
		return new SpatialPass(List.copyOf(blocks), List.copyOf(candidates), anchors.size(), pages,
				normalization.discardedEvidenceCount(), duplicateFlightEvidence);
	}

	private EvidenceNormalization normalizeEvidence(JsonNode root) {
		List<EvidenceBlock> blocks = new ArrayList<>();
		int[] order = { 0 };
		int[] discarded = { 0 };
		normalizeElement(root, new EvidenceContext(0, "unknown", null, null, "$"),
				blocks, order, discarded, "$" );
		blocks.sort(EVIDENCE_ORDER);
		return new EvidenceNormalization(List.copyOf(blocks), discarded[0]);
	}

	private void normalizeElement(JsonNode node, EvidenceContext parent, List<EvidenceBlock> blocks,
			int[] order, int[] discarded, String sourcePath) {
		if (node == null || node.isNull() || node.isMissingNode()) return;
		if (node.isArray()) {
			for (int index = 0; index < node.size(); index++) {
				normalizeElement(node.path(index), parent, blocks, order, discarded,
						sourcePath + "[" + index + "]");
			}
			return;
		}
		if (!node.isObject()) return;

		int pageIndex = pageIndex(node, parent.pageIndex());
		String declaredType = firstNonBlank(valueOrNull(node.path("type")),
				firstNonBlank(valueOrNull(node.path("kind")), valueOrNull(node.path("entityType"))));
		String type = firstNonBlank(declaredType, parent.elementType());
		BoundingBox box = firstNonNull(bounds(node.path("bounds")), bounds(node.path("bbox")),
				bounds(node.path("boundingBox")), bounds(node.path("rect")), parent.box());
		Double confidence = confidence(node, parent.confidence());
		String regionPath = declaredType != null && isRegionNode(declaredType)
				? sourcePath : parent.regionPath();
		EvidenceContext context = new EvidenceContext(pageIndex, type, box, confidence, regionPath);
		Set<String> nodeFingerprints = new LinkedHashSet<>();

		for (String key : List.of("text", "value", "content", "plainText", "rawText")) {
			JsonNode textNode = node.path(key);
			if (textNode.isObject() || textNode.isArray()) {
				normalizeElement(textNode, context, blocks, order, discarded,
						sourcePath + "." + key);
				continue;
			}
			if (!textNode.isValueNode()) continue;
			String text = normalizeEvidenceText(textNode.asText());
			if (text == null) continue;
			String[] lines = text.split("\\n");
			for (int line = 0; line < lines.length; line++) {
				String value = normalizeEvidenceText(lines[line]);
				if (value == null) continue;
				BoundingBox lineBox = splitLineBox(box, line, lines.length);
				int documentOrder = order[0]++;
				String fingerprint = Objects.toString(lineBox) + "|" + value;
				if (!nodeFingerprints.add(fingerprint)) {
					discarded[0]++;
					continue;
				}
				blocks.add(new EvidenceBlock(pageIndex, type, value,
						lineBox == null ? 0.0d : lineBox.x(), lineBox == null ? documentOrder : lineBox.y(),
						lineBox == null ? 0.0d : lineBox.width(), lineBox == null ? 0.0d : lineBox.height(),
						documentOrder, confidence, lineBox != null, sourcePath + "." + key,
						regionPath));
			}
		}

		var fields = node.properties().iterator();
		while (fields.hasNext()) {
			var field = fields.next();
			if (Set.of("text", "value", "content", "plainText", "rawText", "bounds", "bbox",
					"boundingBox", "rect", "page", "pageIndex", "pageNumber", "confidence",
					"score", "type", "kind", "id", "entityType").contains(field.getKey())) continue;
			normalizeElement(field.getValue(), context, blocks, order, discarded,
					sourcePath + "." + field.getKey());
		}
	}

	private boolean isRegionNode(String type) {
		String normalized = Objects.toString(type, "").toLowerCase(Locale.ROOT);
		return normalized.contains("card") || normalized.contains("table")
				|| normalized.contains("row") || normalized.contains("keyvalue")
				|| normalized.contains("region") || normalized.contains("section");
	}

	private Double confidence(JsonNode node, Double fallback) {
		for (JsonNode candidate : List.of(node.path("confidence"), node.path("score"),
				node.path("confidence").path("score"), node.path("metadata").path("confidence"))) {
			if (candidate.isNumber()) return Double.valueOf(clampConfidence(candidate.asDouble()));
		}
		return fallback;
	}

	private int pageIndex(JsonNode node, int fallback) {
		if (node.path("pageIndex").canConvertToInt()) return Math.max(0, node.path("pageIndex").asInt());
		JsonNode page = node.path("page");
		if (page.path("pageIndex").canConvertToInt()) return Math.max(0, page.path("pageIndex").asInt());
		if (page.canConvertToInt()) return Math.max(0, page.asInt());
		if (node.path("pageNumber").canConvertToInt()) return Math.max(0, node.path("pageNumber").asInt() - 1);
		return fallback;
	}

	private BoundingBox splitLineBox(BoundingBox box, int line, int count) {
		if (box == null || count <= 1) return box;
		double height = box.height() / count;
		return new BoundingBox(box.x(), box.y() + height * line, box.width(), height);
	}

	private String normalizeEvidenceText(String text) {
		if (text == null) return null;
		String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
				.replace("\r\n", "\n").replace('\r', '\n')
				.replaceAll("[\\t\\x0B\\f ]+", " ")
				.replaceAll(" *\\n *", "\n").trim();
		return normalized.isBlank() ? null : normalized;
	}

	private List<PositionedToken<String>> flightTokens(List<EvidenceBlock> blocks) {
		List<PositionedToken<String>> tokens = new ArrayList<>();
		for (EvidenceBlock block : blocks) {
			Matcher matcher = FLIGHT_NUMBER_IN_TEXT.matcher(block.text().toUpperCase(Locale.ROOT));
			while (matcher.find()) {
				String normalized = normalizeFlightNumber(matcher.group(1));
				if (normalized != null && validFlightContext(block.text(), matcher.start(), matcher.end())) {
					String matched = matcher.group(1);
					String reason = matched.matches(".*[\\s-].*")
							? "SPATIAL_WITHIN_ELEMENT_JOIN" : "SPATIAL_COMPLETE_TOKEN";
					tokens.add(new PositionedToken<>(block, matcher.start(), normalized,
							block.confidence() == null ? 0.55d : block.confidence(), reason,
							"element " + block.documentOrder()));
				}
			}
		}
		tokens.addAll(joinAdjacentFlightTokens(blocks));
		tokens.sort(TOKEN_ORDER);
		LinkedHashMap<String, PositionedToken<String>> unique = new LinkedHashMap<>();
		for (PositionedToken<String> token : tokens) {
			String key = token.value() + "|" + token.block().documentOrder() + "|" + token.reasonCode();
			unique.putIfAbsent(key, token);
		}
		return List.copyOf(unique.values());
	}

	private List<PositionedToken<String>> joinAdjacentFlightTokens(List<EvidenceBlock> blocks) {
		List<PositionedToken<String>> joined = new ArrayList<>();
		for (int leftIndex = 0; leftIndex < blocks.size(); leftIndex++) {
			EvidenceBlock prefixBlock = blocks.get(leftIndex);
			String prefix = compactToken(prefixBlock.text());
			if (prefix == null || !FLIGHT_PREFIX_FRAGMENT.matcher(prefix).matches()) continue;
			for (int rightIndex = leftIndex + 1; rightIndex < blocks.size() && rightIndex <= leftIndex + 2;
					rightIndex++) {
				EvidenceBlock suffixBlock = blocks.get(rightIndex);
				String suffix = compactToken(suffixBlock.text());
				if (suffix == null || !FLIGHT_SUFFIX_FRAGMENT.matcher(suffix).matches()) continue;
				if (!validFlightContext(prefixBlock.text(), 0, prefixBlock.text().length())
						|| !validFlightContext(suffixBlock.text(), 0, suffixBlock.text().length())
						|| forbiddenJoinContext(blocks, leftIndex, rightIndex)) continue;
				if (!mayJoinFlightFragments(prefixBlock, suffixBlock)) continue;
				String flight = normalizeFlightNumber(prefix + suffix);
				if (flight == null || airlineRegistry.resolveFlightPrefix(flight).candidates().isEmpty()) continue;
				double confidence = Math.min(prefixBlock.confidence() == null ? 0.55d : prefixBlock.confidence(),
						suffixBlock.confidence() == null ? 0.55d : suffixBlock.confidence());
				joined.add(new PositionedToken<>(prefixBlock, 0, flight, confidence,
						"SPATIAL_ADJACENT_TOKENS", "elements " + prefixBlock.documentOrder()
								+ "+" + suffixBlock.documentOrder()));
			}
		}
		return joined;
	}

	private boolean forbiddenJoinContext(List<EvidenceBlock> blocks, int leftIndex, int rightIndex) {
		EvidenceBlock prefix = blocks.get(leftIndex);
		EvidenceBlock suffix = blocks.get(rightIndex);
		StringBuilder context = new StringBuilder();
		for (int index = Math.max(0, leftIndex - 2);
				index <= Math.min(blocks.size() - 1, rightIndex + 2); index++) {
			EvidenceBlock candidate = blocks.get(index);
			if (candidate.pageIndex() != prefix.pageIndex()
					|| !sameLocalRegion(candidate, prefix)
					|| !sameLocalRegion(candidate, suffix)) continue;
			if (candidate.hasBounds() && prefix.hasBounds() && suffix.hasBounds()) {
				double verticalDistance = Math.min(Math.abs(candidate.y() - prefix.y()),
						Math.abs(candidate.y() - suffix.y()));
				if (verticalDistance > Math.max(80.0d,
						Math.max(prefix.height(), suffix.height()) * 4.0d)) continue;
			}
			context.append(' ').append(candidate.text());
		}
		String normalized = Normalizer.normalize(context, Normalizer.Form.NFKC)
				.toUpperCase(Locale.ROOT);
		if (normalized.matches(".*\\b\\d{1,3}\\s*(?:KG|KGS|LB|LBS)\\b.*")) return true;
		return SENSITIVE_CONTEXT_WORDS.stream().anyMatch(normalized::contains);
	}

	private String compactToken(String value) {
		if (value == null) return null;
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
				.toUpperCase(Locale.ROOT).replaceAll("[\\s._/-]+", "");
		return normalized.isBlank() ? null : normalized;
	}

	private boolean mayJoinFlightFragments(EvidenceBlock prefix, EvidenceBlock suffix) {
		if (prefix.pageIndex() != suffix.pageIndex() || !sameLocalRegion(prefix, suffix)) return false;
		if (!prefix.hasBounds() || !suffix.hasBounds()) {
			return Objects.equals(prefix.regionPath(), suffix.regionPath())
					&& suffix.documentOrder() == prefix.documentOrder() + 1;
		}
		double overlap = Math.max(0.0d, Math.min(prefix.y() + prefix.height(), suffix.y() + suffix.height())
				- Math.max(prefix.y(), suffix.y()));
		double minHeight = Math.max(1.0d, Math.min(prefix.height(), suffix.height()));
		if (overlap / minHeight < 0.45d) return false;
		double gap = suffix.x() - (prefix.x() + prefix.width());
		double maxGap = Math.max(24.0d, Math.max(prefix.height(), suffix.height()) * 4.0d);
		return gap >= -Math.min(prefix.width(), suffix.width()) * 0.2d && gap <= maxGap;
	}

	private boolean validFlightContext(String text, int start, int end) {
		String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
		int from = Math.max(0, start - 28);
		int to = Math.min(normalized.length(), end + 28);
		String context = normalized.substring(from, to);
		if (context.matches(".*\\b\\d{1,3}\\s*(?:KG|KGS|LB|LBS)\\b.*")) return false;
		return SENSITIVE_CONTEXT_WORDS.stream().noneMatch(context::contains);
	}

	private List<SegmentAnchor> anchors(List<PositionedToken<String>> tokens,
			List<PositionedToken<String>> airports, List<PositionedToken<DateParts>> dates,
			List<PositionedToken<String>> times, List<PositionedToken<String>> terminals) {
		List<PositionedToken<String>> best = new ArrayList<>();
		for (PositionedToken<String> token : tokens) {
			int duplicate = -1;
			for (int index = 0; index < best.size(); index++) {
				PositionedToken<String> existing = best.get(index);
				if (existing.value().equals(token.value())
						&& sameFlightDisplay(existing, token, airports, dates, times)) {
					duplicate = index;
					break;
				}
			}
			if (duplicate < 0) best.add(token);
			else if (anchorScore(token, airports, times, terminals)
					> anchorScore(best.get(duplicate), airports, times, terminals)) best.set(duplicate, token);
		}
		return best.stream()
				.map(token -> new SegmentAnchor(token.value(), token))
				.sorted((left, right) -> TOKEN_ORDER.compare(left.token(), right.token()))
				.toList();
	}

	private boolean sameFlightDisplay(PositionedToken<String> left, PositionedToken<String> right,
			List<PositionedToken<String>> airports, List<PositionedToken<DateParts>> dates,
			List<PositionedToken<String>> times) {
		if (left.block().pageIndex() != right.block().pageIndex()) return false;
		String leftRegion = Objects.toString(left.block().regionPath(), "$");
		String rightRegion = Objects.toString(right.block().regionPath(), "$");
		if (!"$".equals(leftRegion) || !"$".equals(rightRegion)) return leftRegion.equals(rightRegion);
		DateParts leftDate = governingDateValue(dates, left);
		DateParts rightDate = governingDateValue(dates, right);
		if (leftDate != null && rightDate != null
				&& !Objects.equals(leftDate.date(), rightDate.date())) return false;
		String leftTime = localNearestValue(times, left);
		String rightTime = localNearestValue(times, right);
		if (leftTime != null && rightTime != null && !leftTime.equals(rightTime)) return false;
		TokenRoute leftRoute = localRoute(airports, left);
		TokenRoute rightRoute = localRoute(airports, right);
		if (leftRoute != null && rightRoute != null
				&& (!leftRoute.departure().value().equals(rightRoute.departure().value())
						|| !leftRoute.arrival().value().equals(rightRoute.arrival().value()))) return false;
		boolean bothDense = anchorLocalEvidenceCount(left, airports, times) >= 2
				&& anchorLocalEvidenceCount(right, airports, times) >= 2;
		return !bothDense || evidenceDistance(left, right) <= 120.0d;
	}

	private DateParts governingDateValue(List<PositionedToken<DateParts>> dates,
			PositionedToken<?> anchor) {
		PositionedToken<DateParts> selected = null;
		for (PositionedToken<DateParts> date : dates) {
			if (date.block().pageIndex() != anchor.block().pageIndex()) continue;
			if (comparePosition(date, anchor) >= 0) break;
			if (!arrivalDateContext(date.block().text()) && sameLocalRegion(date.block(), anchor.block())) {
				selected = date;
			}
		}
		return selected == null ? null : selected.value();
	}

	private String localNearestValue(List<PositionedToken<String>> tokens, PositionedToken<?> anchor) {
		PositionedToken<String> nearest = null;
		double bestDistance = Double.MAX_VALUE;
		for (PositionedToken<String> token : tokens) {
			if (token.block().pageIndex() != anchor.block().pageIndex()
					|| !sameLocalRegion(token.block(), anchor.block())) continue;
			double distance = evidenceDistance(token, anchor);
			if (distance < bestDistance && distance <= 100.0d) {
				bestDistance = distance;
				nearest = token;
			}
		}
		return nearest == null ? null : nearest.value();
	}

	private TokenRoute localRoute(List<PositionedToken<String>> airports, PositionedToken<?> anchor) {
		PositionedToken<String> before = null;
		PositionedToken<String> after = null;
		for (PositionedToken<String> airport : airports) {
			if (airport.block().pageIndex() != anchor.block().pageIndex()
					|| !sameLocalRegion(airport.block(), anchor.block())) continue;
			if (comparePosition(airport, anchor) < 0 && evidenceDistance(airport, anchor) <= 160.0d) before = airport;
			if (comparePosition(airport, anchor) > 0 && evidenceDistance(airport, anchor) <= 160.0d) {
				after = airport;
				break;
			}
		}
		return before == null || after == null ? null : new TokenRoute(before, after);
	}

	private int anchorLocalEvidenceCount(PositionedToken<?> anchor,
			List<PositionedToken<String>> airports, List<PositionedToken<String>> times) {
		int count = 0;
		for (PositionedToken<?> token : new ArrayList<PositionedToken<?>>(airports)) {
			if (token.block().pageIndex() == anchor.block().pageIndex()
					&& sameLocalRegion(token.block(), anchor.block())
					&& evidenceDistance(token, anchor) <= 160.0d) count++;
		}
		for (PositionedToken<?> token : new ArrayList<PositionedToken<?>>(times)) {
			if (token.block().pageIndex() == anchor.block().pageIndex()
					&& sameLocalRegion(token.block(), anchor.block())
					&& evidenceDistance(token, anchor) <= 100.0d) count++;
		}
		return count;
	}

	private double anchorScore(PositionedToken<String> token,
			List<PositionedToken<String>> airports, List<PositionedToken<String>> times,
			List<PositionedToken<String>> terminals) {
		String text = token.block().text().replaceAll("\\s", "");
		double isolated = normalizeFlightNumber(text) != null ? 1.0d : 0.0d;
		String type = Objects.toString(token.block().elementType(), "").toLowerCase(Locale.ROOT);
		double structured = type.contains("table") || type.contains("cell") || type.contains("keyvalue")
				? 0.2d : 0.0d;
		return token.confidence() + isolated + structured
				+ localEvidenceScore(token, airports, 0.8d)
				+ localEvidenceScore(token, times, 0.45d)
				+ localEvidenceScore(token, terminals, 0.2d);
	}

	private double localEvidenceScore(PositionedToken<?> anchor,
			List<? extends PositionedToken<?>> evidence, double weight) {
		double score = 0.0d;
		for (PositionedToken<?> token : evidence) {
			if (token.block().pageIndex() != anchor.block().pageIndex()) continue;
			int orderDistance = Math.abs(token.block().documentOrder()
					- anchor.block().documentOrder());
			if (orderDistance > 8) continue;
			score += weight / (1.0d + orderDistance);
		}
		return Math.min(weight * 2.0d, score);
	}

	private List<PositionedToken<String>> airportTokens(List<EvidenceBlock> blocks) {
		List<PositionedToken<String>> tokens = new ArrayList<>();
		for (EvidenceBlock block : blocks) {
			Matcher matcher = IATA_IN_TEXT.matcher(block.text());
			while (matcher.find()) {
				String value = matcher.group(1);
				if (NON_AIRPORT_TOKENS.contains(value)) continue;
				tokens.add(new PositionedToken<>(block, matcher.start(), value,
						block.confidence() == null ? 0.55d : block.confidence()));
			}
		}
		tokens.sort(TOKEN_ORDER);
		return List.copyOf(tokens);
	}

	private List<PositionedToken<DateParts>> dateTokens(List<EvidenceBlock> blocks) {
		List<PositionedToken<DateParts>> tokens = new ArrayList<>();
		for (EvidenceBlock block : blocks) {
			Matcher matcher = DATE_IN_TEXT.matcher(block.text());
			while (matcher.find()) {
				DateParts value = dateParts(matcher.group());
				if (value.hasValue()) tokens.add(new PositionedToken<>(block, matcher.start(), value,
						block.confidence() == null ? 0.55d : block.confidence()));
			}
		}
		tokens.sort(TOKEN_ORDER);
		return List.copyOf(tokens);
	}

	private List<PositionedToken<String>> timeTokens(List<EvidenceBlock> blocks) {
		return textTokens(blocks, TIME_IN_TEXT, matcher -> normalizeTime(matcher.group()));
	}

	private List<PositionedToken<String>> terminalTokens(List<EvidenceBlock> blocks) {
		return textTokens(blocks, TERMINAL_IN_TEXT,
				matcher -> "T" + matcher.group(1).toUpperCase(Locale.ROOT));
	}

	private List<PositionedToken<String>> textTokens(List<EvidenceBlock> blocks, Pattern pattern,
			Function<Matcher, String> valueMapper) {
		List<PositionedToken<String>> tokens = new ArrayList<>();
		for (EvidenceBlock block : blocks) {
			Matcher matcher = pattern.matcher(block.text());
			while (matcher.find()) {
				String value = valueMapper.apply(matcher);
				if (value != null) tokens.add(new PositionedToken<>(block, matcher.start(), value,
						block.confidence() == null ? 0.55d : block.confidence()));
			}
		}
		tokens.sort(TOKEN_ORDER);
		return List.copyOf(tokens);
	}

	private <T> PositionedToken<T> nearestPrevious(List<PositionedToken<T>> tokens,
			PositionedToken<?> anchor, PositionedToken<?> lowerExclusive) {
		PositionedToken<T> result = null;
		for (PositionedToken<T> token : tokens) {
			if (token.block().pageIndex() != anchor.block().pageIndex()) continue;
			if (comparePosition(token, anchor) >= 0) break;
			if (lowerExclusive != null && comparePosition(token, lowerExclusive) <= 0) continue;
			result = token;
		}
		return result;
	}

	private PositionedToken<DateParts> departureDateForAnchor(
			List<PositionedToken<DateParts>> dates, List<SegmentAnchor> anchors, int anchorIndex) {
		SegmentAnchor current = anchors.get(anchorIndex);
		SegmentAnchor previous = anchorIndex == 0 ? null : anchors.get(anchorIndex - 1);
		PositionedToken<DateParts> selected = null;
		for (PositionedToken<DateParts> date : dates) {
			if (date.block().pageIndex() != current.token().block().pageIndex()) continue;
			if (comparePosition(date, current.token()) >= 0) break;
			if (arrivalDateContext(date.block().text())) continue;
			if (!sameLocalRegion(date.block(), current.token().block())) continue;
			if (previous != null && comparePosition(date, previous.token()) > 0
					&& !looksLikeDateHeading(date.block())
					&& evidenceDistance(date, current.token()) >= evidenceDistance(date, previous.token())) {
				// A standalone date that remains closer to the previous flight card is
				// treated as arrival evidence, never as the next departure title.
				continue;
			}
			selected = date;
		}
		return selected;
	}

	private boolean arrivalDateContext(String text) {
		String normalized = Normalizer.normalize(Objects.toString(text, ""), Normalizer.Form.NFKC)
				.toUpperCase(Locale.ROOT);
		return normalized.matches(".*(?:到达|抵达|ARRIVAL|ARRIVE|\\bARR\\b).*");
	}

	private boolean looksLikeDateHeading(EvidenceBlock block) {
		String type = Objects.toString(block.elementType(), "").toLowerCase(Locale.ROOT);
		return type.contains("title") || type.contains("header") || type.contains("heading")
				|| type.contains("section");
	}

	private boolean sameLocalRegion(EvidenceBlock left, EvidenceBlock right) {
		String leftRegion = Objects.toString(left.regionPath(), "$");
		String rightRegion = Objects.toString(right.regionPath(), "$");
		return "$".equals(leftRegion) || "$".equals(rightRegion) || leftRegion.equals(rightRegion);
	}

	private double evidenceDistance(PositionedToken<?> left, PositionedToken<?> right) {
		if (left.block().hasBounds() && right.block().hasBounds()) {
			return Math.abs((left.block().y() + left.block().height() / 2.0d)
					- (right.block().y() + right.block().height() / 2.0d));
		}
		return Math.abs(left.block().documentOrder() - right.block().documentOrder());
	}

	private <T> PositionedToken<T> nearestNext(List<PositionedToken<T>> tokens,
			PositionedToken<?> anchor, PositionedToken<?> upperExclusive) {
		for (PositionedToken<T> token : tokens) {
			if (token.block().pageIndex() != anchor.block().pageIndex()) continue;
			if (comparePosition(token, anchor) <= 0) continue;
			if (upperExclusive != null && comparePosition(token, upperExclusive) >= 0) break;
			return token;
		}
		return null;
	}

	private PositionedToken<String> nearestPreviousAirport(
			List<PositionedToken<String>> airports, PositionedToken<?> anchor,
			PositionedToken<?> lowerExclusive, PositionedToken<?> firstAnchor) {
		PositionedToken<String> fallback = null;
		PositionedToken<String> preferred = null;
		for (PositionedToken<String> token : airports) {
			if (token.block().pageIndex() != anchor.block().pageIndex()) continue;
			if (comparePosition(token, anchor) >= 0) break;
			if (lowerExclusive != null && comparePosition(token, lowerExclusive) <= 0) continue;
			fallback = token;
			if (!weakOverallRouteAirport(token, airports, firstAnchor)) preferred = token;
		}
		return preferred == null ? fallback : preferred;
	}

	private boolean weakOverallRouteAirport(PositionedToken<String> token,
			List<PositionedToken<String>> airports, PositionedToken<?> firstAnchor) {
		if (token.block().pageIndex() != firstAnchor.block().pageIndex()
				|| EVIDENCE_ORDER.compare(token.block(), firstAnchor.block()) >= 0) return false;
		String type = Objects.toString(token.block().elementType(), "").toLowerCase(Locale.ROOT);
		if (type.contains("title") || type.contains("header") || type.contains("heading")) return true;
		long sameBlockAirports = airports.stream()
				.filter(candidate -> candidate.block().equals(token.block())).count();
		if (sameBlockAirports < 2) return false;
		String text = token.block().text();
		return text.contains("→") || text.contains("->") || text.matches("(?i).*\\bTO\\b.*");
	}

	private TokenRoute horizontalRoute(List<PositionedToken<String>> airports,
			PositionedToken<?> anchor, PositionedToken<?> lowerExclusive,
			PositionedToken<?> upperExclusive, PositionedToken<?> firstAnchor) {
		List<PositionedToken<String>> samePage = airports.stream()
				.filter(token -> token.block().pageIndex() == anchor.block().pageIndex())
				.filter(token -> lowerExclusive == null || comparePosition(token, lowerExclusive) > 0)
				.filter(token -> upperExclusive == null || comparePosition(token, upperExclusive) < 0)
				.filter(token -> !weakOverallRouteAirport(token, airports, firstAnchor))
				.toList();
		if (samePage.size() < 2) return null;
		if (anchor.block().height() <= 0.0d || anchor.block().width() <= 0.0d) return null;
		double band = anchor.block().height() * 2.5d;
		double anchorCenterX = tokenCenterX(anchor);
		double anchorCenterY = anchor.block().y() + anchor.block().height() / 2.0d;
		PositionedToken<String> left = null;
		PositionedToken<String> right = null;
		for (PositionedToken<String> token : samePage) {
			double centerY = token.block().y() + token.block().height() / 2.0d;
			if (Math.abs(centerY - anchorCenterY) > band) continue;
			double centerX = tokenCenterX(token);
			if (centerX <= anchorCenterX && (left == null || centerX > tokenCenterX(left))) left = token;
			if (centerX > anchorCenterX && (right == null || centerX < tokenCenterX(right))) right = token;
		}
		if (left == null || right == null || left.value().equals(right.value())) return null;
		double leftY = left.block().y() + left.block().height() / 2.0d;
		double rightY = right.block().y() + right.block().height() / 2.0d;
		return Math.abs(leftY - rightY) <= band / 2.0d ? new TokenRoute(left, right) : null;
	}

	private double tokenCenterX(PositionedToken<?> token) {
		EvidenceBlock block = token.block();
		int textLength = block.text() == null ? 0 : block.text().length();
		if (textLength <= 0 || block.width() <= 0.0d) return block.x() + block.width() / 2.0d;
		int valueLength = Math.max(1, Objects.toString(token.value(), "").length());
		double characterCenter = Math.min(textLength,
				Math.max(0.0d, token.characterOffset() + valueLength / 2.0d));
		return block.x() + block.width() * characterCenter / textLength;
	}

	private int comparePosition(PositionedToken<?> left, PositionedToken<?> right) {
		return TOKEN_ORDER.compare(left, right);
	}

	private <T> T tokenValue(PositionedToken<T> token) {
		return token == null ? null : token.value();
	}

	private void putSpatialField(Map<String, ExtractedField> fields, String name, String value,
			PositionedToken<?> token) {
		if (token == null || value == null) return;
		EvidenceBlock block = token.block();
		fields.put(name, new ExtractedField(value, value, token.confidence(), block.pageIndex(),
				block.box(), "Nutrient Structure Spatial " + token.evidenceReference()));
	}

	private MergeOutcome mergeCandidates(List<SegmentCandidate> understand,
			List<SegmentCandidate> structure) {
		List<MergedEntry> entries = new ArrayList<>();
		for (SegmentCandidate candidate : structure == null ? List.<SegmentCandidate>of() : structure) {
			if (candidate != null && candidate.segment() != null) {
				entries.add(new MergedEntry(candidate.segment(), candidate.documentOrder(),
						CandidateSource.STRUCTURE, new LinkedHashSet<>()));
			}
		}
		for (SegmentCandidate candidate : understand == null ? List.<SegmentCandidate>of() : understand) {
			if (candidate == null || candidate.segment() == null) continue;
			String key = normalizeFlightNumber(candidate.flightNumber());
			Integer target = key == null ? null : matchingStructureFlight(entries, candidate, key);
			if (target == null && key != null) target = matchingStructureEvidence(entries, candidate);
			if (target == null && key == null) target = matchingStructureOrder(entries, candidate.documentOrder());
			if (target == null) {
				entries.add(new MergedEntry(candidate.segment(), candidate.documentOrder(),
						candidate.source(), new LinkedHashSet<>()));
				continue;
			}
			MergedEntry entry = entries.get(target);
			entry.segment = entry.source == CandidateSource.STRUCTURE
					? mergeSameSegment(candidate.segment(), entry.segment, true, entry.conflicts)
					: mergeSameSegment(entry.segment, candidate.segment(), false, entry.conflicts);
		}
		return new MergeOutcome(entries.stream().map(entry -> entry.segment).toList(),
				entries.stream().map(entry -> Set.copyOf(entry.conflicts)).toList());
	}

	private Integer matchingStructureFlight(List<MergedEntry> entries,
			SegmentCandidate candidate, String flightNumber) {
		int bestScore = Integer.MIN_VALUE;
		Integer bestIndex = null;
		boolean tied = false;
		for (int index = 0; index < entries.size(); index++) {
			MergedEntry entry = entries.get(index);
			if (entry.source != CandidateSource.STRUCTURE
					|| !flightNumber.equals(normalizeFlightNumber(entry.segment.flightNumber()))) continue;
			int score = mergeEvidenceScore(candidate.segment(), candidate.documentOrder(), entry);
			if (score > bestScore) {
				bestScore = score;
				bestIndex = index;
				tied = false;
			} else if (score == bestScore) tied = true;
		}
		return tied ? null : bestIndex;
	}

	private Integer matchingStructureOrder(List<MergedEntry> entries, int documentOrder) {
		for (int index = 0; index < entries.size(); index++) {
			MergedEntry entry = entries.get(index);
			if (entry.source == CandidateSource.STRUCTURE && entry.documentOrder == documentOrder) return index;
		}
		return null;
	}

	private Integer matchingStructureEvidence(List<MergedEntry> entries, SegmentCandidate candidate) {
		String candidateSuffix = flightNumericSuffix(candidate.flightNumber());
		if (candidateSuffix == null) return null;
		int bestScore = 0;
		Integer bestIndex = null;
		boolean tied = false;
		for (int index = 0; index < entries.size(); index++) {
			MergedEntry entry = entries.get(index);
			if (entry.source != CandidateSource.STRUCTURE
					|| !candidateSuffix.equals(flightNumericSuffix(entry.segment.flightNumber()))) continue;
			int score = mergeEvidenceScore(candidate.segment(), candidate.documentOrder(), entry);
			if (score > bestScore) {
				bestScore = score;
				bestIndex = index;
				tied = false;
			} else if (score > 0 && score == bestScore) tied = true;
		}
		return bestScore >= 4 && !tied ? bestIndex : null;
	}

	private int mergeEvidenceScore(DocumentFlightSegment candidate, int documentOrder, MergedEntry entry) {
		DocumentFlightSegment spatial = entry.segment;
		int score = documentOrder == entry.documentOrder ? 4 : 0;
		if (sameNonNull(candidate.departureAirport(), spatial.departureAirport())) score += 3;
		if (sameNonNull(candidate.arrivalAirport(), spatial.arrivalAirport())) score += 3;
		if (sameNonNull(candidate.departureDate(), spatial.departureDate())) score += 2;
		if (sameNonNull(timePart(candidate.scheduledDeparture()), timePart(spatial.scheduledDeparture()))) score += 2;
		if (candidate.page() != null && candidate.page().equals(spatial.page())) score += 1;
		return score;
	}

	private boolean sameNonNull(Object left, Object right) {
		return left != null && Objects.equals(left, right);
	}

	private String flightNumericSuffix(String value) {
		String normalized = normalizeFlightNumber(value);
		if (normalized == null) return null;
		Matcher matcher = FLIGHT_NUMERIC_SUFFIX.matcher(normalized);
		return matcher.find() ? matcher.group(1) : null;
	}

	private DocumentFlightSegment mergeSameSegment(DocumentFlightSegment understand,
			DocumentFlightSegment spatial, boolean spatialPreferred, Set<String> conflicts) {
		boolean completeSpatialRoute = spatialPreferred
				&& spatial.departureAirport() != null && spatial.arrivalAirport() != null;
		FlightMerge flightMerge = mergeFlightNumber(understand, spatial, conflicts);
		String flight = flightMerge.flightNumber();
		DateMerge departureDate = mergeDepartureDate(understand, spatial, conflicts);
		String departure = mergeScalar(understand.departureAirport(), spatial.departureAirport(),
				"departure_airport", conflicts, completeSpatialRoute);
		String arrival = mergeScalar(understand.arrivalAirport(), spatial.arrivalAirport(),
				"arrival_airport", conflicts, completeSpatialRoute);
		String scheduledDeparture = mergeSchedule(understand.scheduledDeparture(),
				spatial.scheduledDeparture(), "scheduled_departure", conflicts);
		String scheduledArrival = mergeSchedule(understand.scheduledArrival(), spatial.scheduledArrival(),
				"scheduled_arrival", conflicts);
		String boarding = mergeScalar(understand.boardingTime(), spatial.boardingTime(),
				"boarding_time", conflicts, false);
		String terminal = mergeScalar(understand.terminal(), spatial.terminal(), "terminal", conflicts, false);
		String arrivalTerminal = mergeScalar(fieldValue(understand, "arrival_terminal"),
				fieldValue(spatial, "arrival_terminal"), "arrival_terminal", conflicts, false);
		String gate = mergeScalar(understand.gate(), spatial.gate(), "gate", conflicts, false);
		String seat = mergeScalar(understand.seat(), spatial.seat(), "seat", conflicts, false);
		Map<String, ExtractedField> evidence = mergeEvidence(understand.fields(), spatial.fields(),
				flight, departureDate.date(), departureDate.monthDay(), departure, arrival,
				scheduledDeparture, scheduledArrival, boarding, terminal, arrivalTerminal, gate, seat);
		return new DocumentFlightSegment(flight, departureDate.date(), departureDate.monthDay(), departure, arrival,
				scheduledDeparture, scheduledArrival, boarding, terminal, gate, seat,
				Math.max(understand.confidence(), spatial.confidence()),
				spatial.page() != null ? spatial.page() : understand.page(),
				spatial.boundingBox() != null ? spatial.boundingBox() : understand.boundingBox(), evidence,
				flightMerge.assessment().confidence(), flightMerge.assessment().requiresConfirmation(), false,
				departureDate.source());
	}

	private FlightMerge mergeFlightNumber(DocumentFlightSegment understand,
			DocumentFlightSegment spatial, Set<String> conflicts) {
		String left = normalizeFlightNumber(understand.flightNumber());
		String right = normalizeFlightNumber(spatial.flightNumber());
		if (left == null) return new FlightMerge(right, assessmentOf(spatial));
		if (right == null) return new FlightMerge(left, assessmentOf(understand));
		if (left.equals(right)) {
			FlightAssessment combined = strongerAssessment(assessmentOf(understand), assessmentOf(spatial));
			AirlineResolution registry = airlineRegistry.resolveFlightPrefix(left);
			if (registry.isHighConfidence()) {
				combined = new FlightAssessment(left, FlightNumberConfidence.HIGH, false,
						"UNDERSTAND_STRUCTURE_AGREEMENT");
			}
			return new FlightMerge(left, combined);
		}
		FlightAssessment leftAssessment = assessmentOf(understand);
		FlightAssessment rightAssessment = assessmentOf(spatial);
		int comparison = confidenceRank(leftAssessment.confidence())
				- confidenceRank(rightAssessment.confidence());
		if (comparison > 0) {
			conflicts.add("flight_number_resolved");
			return new FlightMerge(left, leftAssessment);
		}
		if (comparison < 0) {
			conflicts.add("flight_number_resolved");
			return new FlightMerge(right, rightAssessment);
		}
		conflicts.add("flight_number");
		return new FlightMerge(null, FlightAssessment.LOW);
	}

	private FlightAssessment assessmentOf(DocumentFlightSegment segment) {
		return new FlightAssessment(segment.flightNumber(), segment.flightNumberConfidence(),
				segment.requiresFlightNumberConfirmation(), "MERGED_EVIDENCE");
	}

	private FlightAssessment strongerAssessment(FlightAssessment left, FlightAssessment right) {
		return confidenceRank(left.confidence()) >= confidenceRank(right.confidence()) ? left : right;
	}

	private int confidenceRank(FlightNumberConfidence confidence) {
		return switch (confidence == null ? FlightNumberConfidence.LOW : confidence) {
			case HIGH -> 3;
			case MEDIUM -> 2;
			case LOW -> 1;
		};
	}

	private <T> T mergeScalar(T understand, T spatial, String field, Set<String> conflicts,
			boolean preferSpatial) {
		if (understand == null) return spatial;
		if (spatial == null || Objects.equals(understand, spatial)) return understand;
		conflicts.add(field);
		return preferSpatial ? spatial : null;
	}

	private DateMerge mergeDepartureDate(DocumentFlightSegment understand,
			DocumentFlightSegment spatial, Set<String> conflicts) {
		DateParts understandDate = new DateParts(understand.departureDate(),
				understand.departureMonthDay(), understand.dateSource());
		DateParts spatialDate = new DateParts(spatial.departureDate(),
				spatial.departureMonthDay(), spatial.dateSource());
		DateParts selected = combineDateEvidence(understandDate, spatialDate);
		if (selected.source() == DateSource.CONFLICT) {
			conflicts.add("departure_date");
			return DateMerge.CONFLICT;
		}
		return new DateMerge(selected.date(), selected.monthDay(), selected.source());
	}

	private String mergeSchedule(String understand, String spatial, String field, Set<String> conflicts) {
		if (understand == null) return spatial;
		if (spatial == null || understand.equals(spatial)) return understand;
		String understandTime = timePart(understand);
		String spatialTime = timePart(spatial);
		if (understandTime != null && understandTime.equals(spatialTime)) return understand;
		conflicts.add(field);
		return null;
	}

	private Map<String, ExtractedField> mergeEvidence(Map<String, ExtractedField> understand,
			Map<String, ExtractedField> spatial, String flight, LocalDate date, String monthDay,
			String departure, String arrival, String scheduledDeparture, String scheduledArrival,
			String boarding, String terminal, String arrivalTerminal, String gate, String seat) {
		Map<String, String> finalValues = new LinkedHashMap<>();
		finalValues.put("flight_number", flight);
		finalValues.put("departure_date", date == null ? monthDay : date.toString());
		finalValues.put("departure_airport", departure);
		finalValues.put("arrival_airport", arrival);
		finalValues.put("scheduled_departure", scheduledDeparture);
		finalValues.put("scheduled_arrival", scheduledArrival);
		finalValues.put("boarding_time", boarding);
		finalValues.put("terminal", terminal);
		finalValues.put("departure_terminal", terminal);
		finalValues.put("arrival_terminal", arrivalTerminal);
		finalValues.put("gate", gate);
		finalValues.put("seat", seat);
		Map<String, ExtractedField> merged = emptyFields();
		for (String name : SEGMENT_FIELD_NAMES) {
			String value = finalValues.get(name);
			ExtractedField selected = evidenceForValue(value, spatial.get(name), understand.get(name));
			if (selected != null) merged.put(name, selected);
			else if (value != null) merged.put(name, new ExtractedField(value, value, 0.0d,
					null, null, "Local deterministic merge"));
		}
		return merged;
	}

	private String fieldValue(DocumentFlightSegment segment, String name) {
		ExtractedField field = segment.fields().get(name);
		return field == null ? null : field.value();
	}

	private ExtractedField evidenceForValue(String value, ExtractedField... fields) {
		if (value == null) return new ExtractedField(null, null, 0.0d, null, null, null);
		for (ExtractedField field : fields) {
			if (field != null && Objects.equals(value, field.value())) return field;
		}
		return null;
	}

	private BoardingPassExtractionResponse auditedResponse(BoardingPassExtractionResponse base,
			MergeOutcome merged, boolean structureSucceeded, SpatialPass spatial) {
		List<DocumentFlightSegment> segments = merged.segments();
		LinkedHashSet<String> review = new LinkedHashSet<>();
		propagateBaseReviewFields(review, base, segments);
		if (!structureSucceeded) review.add("spatial_review_unavailable");
		for (int index = 0; index < segments.size(); index++) {
			DocumentFlightSegment segment = segments.get(index);
			addMissingReviewFields(review, segment, index);
			for (String conflict : merged.conflicts().get(index)) {
				review.add("segments[" + index + "].conflict." + conflict);
			}
		}
		if (spatial != null && hasUnboundSpatialFlight(spatial, segments)) {
			review.add("spatial_unbound_flight_number");
		}
		if (hasDuplicateSegments(segments)) review.add("duplicate_flight_number");
		if (spatial != null && spatial.duplicateFlightEvidenceCount() > 0) {
			review.add("spatial_duplicate_flight_number");
		}
		if (hasNonAdjacentAirportEvidenceReuse(segments)) {
			review.add("cross_segment_airport_conflict");
		}
		if (spatial != null && spatial.discardedEvidenceCount() > 0) {
			review.add("spatial_evidence_discarded");
		}
		for (int index = 0; index < segments.size(); index++) {
			reconcileResolvedReviewFields(review, segments.get(index), index, segments.size());
		}

		List<String> reviewFields = review.stream().map(field -> segments.size() == 1
				? field.replace("segments[0].", "") : field).toList();
		Map<String, ExtractedField> rootFields = new LinkedHashMap<>(base.fields());
		if (segments.size() == 1) rootFields.putAll(segments.get(0).fields());
		return new BoardingPassExtractionResponse(base.documentType(), null, null, null,
				null, null, null, null, null, null, null, null, false, false, rootFields,
				segments, reviewFields, DATA_SOURCE, base.extractedAt());
	}

	private void reconcileResolvedReviewFields(Set<String> review, DocumentFlightSegment segment,
			int index, int segmentCount) {
		String prefix = segmentCount == 1 ? "" : "segments[" + index + "].";
		if (segment.flightNumber() != null && !segment.requiresFlightNumberConfirmation()) {
			review.remove(prefix + "flight_number");
		}
		if (segment.departureDate() != null) review.remove(prefix + "departure_date");
		if (segment.departureAirport() != null) review.remove(prefix + "departure_airport");
		if (segment.arrivalAirport() != null) review.remove(prefix + "arrival_airport");
	}

	private void propagateBaseReviewFields(Set<String> review,
			BoardingPassExtractionResponse base, List<DocumentFlightSegment> mergedSegments) {
		List<DocumentFlightSegment> baseSegments = base.segments();
		for (String rawField : base.needsReviewFields()) {
			String field = Objects.toString(rawField, "");
			if (field.isBlank()) continue;
			Matcher indexed = SEGMENT_REVIEW_PATTERN.matcher(field);
			int sourceIndex = -1;
			String segmentField = null;
			if (indexed.matches()) {
				sourceIndex = Integer.parseInt(indexed.group(1));
				segmentField = indexed.group(2);
			} else if (baseSegments.size() == 1 && isSegmentReviewField(field)) {
				sourceIndex = 0;
				segmentField = field;
			}
			if (segmentField == null) {
				review.add(field);
				continue;
			}
			int targetIndex = mergedIndexForBaseSegment(baseSegments, sourceIndex, mergedSegments);
			if (targetIndex < 0) continue;
			review.add(mergedSegments.size() == 1 ? segmentField
					: "segments[" + targetIndex + "]." + segmentField);
		}
	}

	private boolean isSegmentReviewField(String field) {
		return field.startsWith("conflict.") || SEGMENT_FIELD_NAMES.contains(field);
	}

	private int mergedIndexForBaseSegment(List<DocumentFlightSegment> baseSegments,
			int sourceIndex, List<DocumentFlightSegment> mergedSegments) {
		if (sourceIndex < 0 || sourceIndex >= baseSegments.size()) return -1;
		String sourceFlight = normalizeFlightNumber(baseSegments.get(sourceIndex).flightNumber());
		if (sourceFlight != null) {
			for (int index = 0; index < mergedSegments.size(); index++) {
				if (sourceFlight.equals(normalizeFlightNumber(mergedSegments.get(index).flightNumber()))) {
					return index;
				}
			}
		}
		return sourceIndex < mergedSegments.size() ? sourceIndex : -1;
	}

	private BoardingPassExtractionResponse emptyResponse() {
		return new BoardingPassExtractionResponse(DocumentType.UNKNOWN, null, null, null,
				null, null, null, null, null, null, null, null, false, false, Map.of(),
				List.of(), List.of("document_type"), DATA_SOURCE, Instant.now(clock));
	}

	private void addMissingReviewFields(java.util.Collection<String> review,
			DocumentFlightSegment segment, int index) {
		String prefix = "segments[" + index + "].";
		if (segment.flightNumber() == null) review.add(prefix + "flight_number");
		else if (segment.requiresFlightNumberConfirmation()) review.add(prefix + "flight_number");
		if (segment.departureDate() == null) review.add(prefix + "departure_date");
		if (segment.departureAirport() == null) review.add(prefix + "departure_airport");
		if (segment.arrivalAirport() == null) review.add(prefix + "arrival_airport");
	}

	private void logOutcome(BoardingPassExtractionResponse result, boolean understandSucceeded,
			boolean structureSucceeded, SpatialPass spatial, MergeOutcome merged) {
		long incomplete = result.segments().stream().filter(segment -> segment.flightNumber() == null
				|| segment.departureDate() == null || segment.departureAirport() == null
				|| segment.arrivalAirport() == null).count();
		long conflicts = merged.conflicts().stream().filter(values -> !values.isEmpty()).count();
		LOGGER.info("documentExtraction understandSucceeded={} structureSucceeded={} structureElementCount={} structurePageCount={} structureDistinctFlightCount={} structureDuplicateFlightEvidenceCount={} structureDiscardedEvidenceCount={} finalSegmentCount={} incompleteSegmentCount={} conflictSegmentCount={}",
				understandSucceeded, structureSucceeded, spatial == null ? 0 : spatial.blocks().size(),
				spatial == null ? 0 : spatial.pageCount(), spatial == null ? 0 : spatial.distinctFlightCount(),
				spatial == null ? 0 : spatial.duplicateFlightEvidenceCount(),
				spatial == null ? 0 : spatial.discardedEvidenceCount(), result.segments().size(),
				incomplete, conflicts);
	}

	private boolean hasUnboundSpatialFlight(SpatialPass spatial,
			List<DocumentFlightSegment> segments) {
		Set<String> finalFlights = new LinkedHashSet<>();
		for (DocumentFlightSegment segment : segments) {
			String flight = normalizeFlightNumber(segment.flightNumber());
			if (flight != null) finalFlights.add(flight);
		}
		for (SegmentCandidate candidate : spatial.candidates()) {
			String flight = normalizeFlightNumber(candidate.flightNumber());
			if (flight != null && !finalFlights.contains(flight)) return true;
		}
		return false;
	}

	private boolean hasDuplicateSegments(List<DocumentFlightSegment> segments) {
		Set<String> seen = new LinkedHashSet<>();
		for (DocumentFlightSegment segment : segments) {
			String flight = normalizeFlightNumber(segment.flightNumber());
			if (flight == null) continue;
			String identity = flight + "|" + segment.departureDate() + "|" + segment.page()
					+ "|" + segment.departureAirport() + "|" + segment.arrivalAirport()
					+ "|" + timePart(segment.scheduledDeparture());
			if (!seen.add(identity)) return true;
		}
		return false;
	}

	private boolean hasNonAdjacentAirportEvidenceReuse(List<DocumentFlightSegment> segments) {
		Map<String, Integer> firstSegmentByEvidence = new LinkedHashMap<>();
		for (int index = 0; index < segments.size(); index++) {
			DocumentFlightSegment segment = segments.get(index);
			for (String fieldName : List.of("departure_airport", "arrival_airport")) {
				ExtractedField field = segment.fields().get(fieldName);
				if (field == null || field.value() == null || field.sourceLabel() == null
						|| !field.sourceLabel().startsWith("Nutrient Structure Spatial element ")) continue;
				Integer first = firstSegmentByEvidence.putIfAbsent(field.sourceLabel(), index);
				if (first != null && Math.abs(index - first) > 1) return true;
			}
		}
		return false;
	}

	private ExtractedField sanitizeField(String name, ExtractedField field) {
		String raw = field.rawValue();
		String value = switch (name) {
			case "flight_number" -> normalizeFlightNumber(raw);
			case "departure_date" -> dateParts(raw).publicValue();
			case "departure_airport", "arrival_airport" -> normalizeAirport(raw);
			case "scheduled_departure", "scheduled_arrival" -> validScheduleValue(raw);
			case "boarding_time" -> normalizeTime(raw);
			case "terminal", "departure_terminal", "arrival_terminal", "gate", "seat" -> safeShortValue(raw);
			default -> null;
		};
		return new ExtractedField(value, raw, field.confidence(), field.page(), field.boundingBox(),
				field.sourceLabel());
	}

	private Map<String, ExtractedField> emptyFields() {
		Map<String, ExtractedField> fields = new LinkedHashMap<>();
		for (String name : SEGMENT_FIELD_NAMES) {
			fields.put(name, new ExtractedField(null, null, 0.0d, null, null, null));
		}
		return fields;
	}

	private Map<String, String> fieldSources(DocumentFlightSegment segment) {
		Map<String, String> sources = new LinkedHashMap<>();
		segment.fields().forEach((name, field) -> {
			if (field != null && field.value() != null) sources.put(name, field.sourceLabel());
		});
		return Map.copyOf(sources);
	}

	private boolean hasLegacySegment(JsonNode data) {
		return SEGMENT_FIELD_NAMES.stream().anyMatch(name -> valueOrNull(data.path(name)) != null);
	}

	private JsonNode segmentMetadata(JsonNode metadata, int index) {
		JsonNode segments = metadata.path("segments");
		if (segments.isArray()) return segments.path(index);
		if (segments.isObject() && !segments.path(Integer.toString(index)).isMissingNode()) {
			return segments.path(Integer.toString(index));
		}
		return metadata;
	}

	private ExtractedField extractedField(JsonNode data, JsonNode metadata, String name, String sourcePath) {
		String raw = valueOrNull(data.path(name));
		JsonNode fieldMetadata = metadata.path(name);
		double confidence = clampConfidence(fieldMetadata.path("confidence").asDouble(0.0d));
		Integer page = fieldMetadata.path("pageIndex").isNumber()
				? fieldMetadata.path("pageIndex").asInt() : null;
		BoundingBox box = firstNonNull(bounds(fieldMetadata.path("bounds")), bounds(fieldMetadata.path("bbox")));
		return new ExtractedField(raw, raw, confidence, page, box, "Nutrient Understand citation: " + sourcePath);
	}

	private BoundingBox bounds(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) return null;
		if (node.isArray() && node.size() >= 4 && node.path(0).isNumber()
				&& node.path(1).isNumber() && node.path(2).isNumber() && node.path(3).isNumber()) {
			return new BoundingBox(node.path(0).asDouble(), node.path(1).asDouble(),
					node.path(2).asDouble(), node.path(3).asDouble());
		}
		if (!node.isObject()) return null;
		JsonNode xNode = node.path("x").isNumber() ? node.path("x") : node.path("left");
		JsonNode yNode = node.path("y").isNumber() ? node.path("y") : node.path("top");
		if (!xNode.isNumber() || !yNode.isNumber()) return null;
		double x = xNode.asDouble();
		double y = yNode.asDouble();
		double width;
		double height;
		if (node.path("width").isNumber() && node.path("height").isNumber()) {
			width = node.path("width").asDouble();
			height = node.path("height").asDouble();
		} else if (node.path("right").isNumber() && node.path("bottom").isNumber()) {
			width = node.path("right").asDouble() - x;
			height = node.path("bottom").asDouble() - y;
		} else return null;
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width)
				|| !Double.isFinite(height) || width < 0.0d || height < 0.0d) return null;
		return new BoundingBox(x, y, width, height);
	}

	private String valueOrNull(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull() || !node.isValueNode()) return null;
		String value = node.asText().trim();
		return value.isBlank() ? null : value;
	}

	String normalizeFlightNumber(String value) {
		if (value == null) return null;
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
				.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
		return FLIGHT_NUMBER_PATTERN.matcher(normalized).matches() ? normalized : null;
	}

	private FlightAssessment assessUnderstandFlight(ExtractedField field) {
		String flight = field == null ? null : normalizeFlightNumber(field.value());
		if (flight == null) return FlightAssessment.LOW;
		AirlineResolution resolution = airlineRegistry.resolveFlightPrefix(flight);
		if (resolution.candidates().isEmpty()) return FlightAssessment.LOW;
		double providerConfidence = field.confidence();
		if (providerConfidence > 0.0d && providerConfidence < 0.50d) return FlightAssessment.LOW;
		return new FlightAssessment(flight, FlightNumberConfidence.MEDIUM, true,
				resolution.ambiguous() ? "AIRLINE_PREFIX_AMBIGUOUS" : "UNDERSTAND_SINGLE_CHANNEL");
	}

	private FlightAssessment assessSpatialFlight(SegmentAnchor anchor) {
		if (anchor == null || anchor.token() == null) return FlightAssessment.LOW;
		String flight = normalizeFlightNumber(anchor.flightNumber());
		if (flight == null) return FlightAssessment.LOW;
		AirlineResolution resolution = airlineRegistry.resolveFlightPrefix(flight);
		if (resolution.candidates().isEmpty()) return FlightAssessment.LOW;
		boolean reliableGeometry = anchor.token().confidence() >= 0.75d
				&& ("SPATIAL_COMPLETE_TOKEN".equals(anchor.token().reasonCode())
						|| "SPATIAL_WITHIN_ELEMENT_JOIN".equals(anchor.token().reasonCode())
						|| "SPATIAL_ADJACENT_TOKENS".equals(anchor.token().reasonCode()));
		if (resolution.isHighConfidence() && reliableGeometry) {
			return new FlightAssessment(flight, FlightNumberConfidence.HIGH, false,
					anchor.token().reasonCode());
		}
		return new FlightAssessment(flight, FlightNumberConfidence.MEDIUM, true,
				resolution.ambiguous() ? "AIRLINE_PREFIX_AMBIGUOUS" : anchor.token().reasonCode());
	}


	private String normalizeAirport(String value) {
		if (value == null) return null;
		String visible = Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
		if (visible.matches("[A-Z]{3}")) return visible;
		LinkedHashSet<String> explicit = new LinkedHashSet<>();
		Matcher matcher = IATA_IN_TEXT.matcher(visible);
		while (matcher.find()) {
			if (!NON_AIRPORT_TOKENS.contains(matcher.group(1))) explicit.add(matcher.group(1));
		}
		return explicit.size() == 1 ? explicit.iterator().next() : null;
	}

	DateParts dateParts(String value) {
		if (value == null || value.isBlank()) return DateParts.EMPTY;
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
		try {
			LocalDate date = LocalDate.parse(normalized);
			return DateParts.of(date);
		} catch (DateTimeParseException ignored) {
			// Continue with explicitly supported visible formats.
		}
		Matcher fullChinese = FULL_CHINESE_DATE.matcher(normalized);
		if (fullChinese.matches()) return DateParts.of(localDate(fullChinese.group(1),
				fullChinese.group(2), fullChinese.group(3)));
		Matcher fullSlash = SLASH_FULL_DATE.matcher(normalized);
		if (fullSlash.matches()) return DateParts.of(localDate(fullSlash.group(1),
				fullSlash.group(2), fullSlash.group(3)));
		Matcher chinese = MONTH_DAY_CHINESE.matcher(normalized);
		if (chinese.matches()) return monthDayParts(chinese.group(1), chinese.group(2));
		Matcher slash = SLASH_MONTH_DAY.matcher(normalized);
		if (slash.matches()) return monthDayParts(slash.group(1), slash.group(2));
		Matcher normalizedMonthDay = NORMALIZED_MONTH_DAY.matcher(normalized);
		if (normalizedMonthDay.matches()) {
			return monthDayParts(normalizedMonthDay.group(1), normalizedMonthDay.group(2));
		}
		Matcher monthFirst = ENGLISH_MONTH_FIRST.matcher(normalized);
		if (monthFirst.matches()) return englishDateParts(monthFirst.group(1), monthFirst.group(2),
				monthFirst.group(3));
		Matcher dayFirst = ENGLISH_DAY_FIRST.matcher(normalized);
		if (dayFirst.matches()) return englishDateParts(dayFirst.group(2), dayFirst.group(1),
				dayFirst.group(3));
		return DateParts.EMPTY;
	}

	private DateParts englishDateParts(String monthName, String day, String year) {
		Integer month = ENGLISH_MONTHS.get(monthName.toLowerCase(Locale.ROOT));
		if (month == null) return DateParts.EMPTY;
		return year == null ? monthDayParts(Integer.toString(month), day)
				: DateParts.of(localDate(year, Integer.toString(month), day));
	}

	private DateParts monthDayParts(String monthText, String dayText) {
		try {
			int month = Integer.parseInt(monthText);
			int day = Integer.parseInt(dayText);
			LocalDate completed = LocalDate.of(LocalDate.now(clock).getYear(), month, day);
			return new DateParts(completed, "%02d-%02d".formatted(month, day),
					DateSource.CURRENT_YEAR_FROM_MONTH_DAY);
		} catch (DateTimeException | NumberFormatException exception) {
			return DateParts.EMPTY;
		}
	}

	private LocalDate localDate(String year, String month, String day) {
		try {
			return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
		} catch (DateTimeException | NumberFormatException exception) {
			return null;
		}
	}

	private DateParts datePartsFromDateTime(String value) {
		if (value == null) return DateParts.EMPTY;
		try {
			return DateParts.of(OffsetDateTime.parse(value).toLocalDate());
		} catch (DateTimeParseException ignored) {
			// Most printed document times are local and intentionally have no offset.
		}
		try {
			return DateParts.of(LocalDateTime.parse(value).toLocalDate());
		} catch (DateTimeParseException ignored) {
			return DateParts.EMPTY;
		}
	}

	private String validScheduleValue(String value) {
		if (value == null) return null;
		try {
			OffsetDateTime.parse(value);
			return value;
		} catch (DateTimeParseException ignored) {
			// Continue with local date-time and time-only formats.
		}
		try {
			LocalDateTime.parse(value);
			return value;
		} catch (DateTimeParseException ignored) {
			return normalizeTime(value);
		}
	}

	private String normalizeTime(String value) {
		if (value == null) return null;
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
		Matcher matcher = Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)$").matcher(normalized);
		if (!matcher.matches()) return null;
		LocalTime time = LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
		return "%02d:%02d".formatted(time.getHour(), time.getMinute());
	}

	private String timePart(String value) {
		if (value == null) return null;
		Matcher matcher = TIME_IN_TEXT.matcher(value);
		return matcher.find() ? normalizeTime(matcher.group()) : null;
	}

	private String safeShortValue(String value) {
		if (value == null) return null;
		String cleaned = Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
		return cleaned.length() <= 24 && cleaned.matches("[\\p{L}\\p{N} ./-]+") ? cleaned : null;
	}

	private double segmentConfidence(Map<String, ExtractedField> fields) {
		return fields.entrySet().stream()
				.filter(entry -> REQUIRED_SEGMENT_FIELDS.contains(entry.getKey()))
				.mapToDouble(entry -> entry.getValue().confidence())
				.filter(value -> value > 0.0d).average().orElse(0.0d);
	}

	private double confidenceThreshold(String name) {
		return "flight_number".equals(name) || "departure_date".equals(name) ? 0.8d : 0.65d;
	}

	private double clampConfidence(double value) {
		return Double.isFinite(value) ? Math.max(0.0d, Math.min(1.0d, value)) : 0.0d;
	}

	private String monthDay(LocalDate date) {
		return date == null ? null : "%02d-%02d".formatted(date.getMonthValue(), date.getDayOfMonth());
	}

	private String firstNonBlank(String first, String second) {
		return first == null || first.isBlank() ? second : first;
	}

	@SafeVarargs
	private final <T> T firstNonNull(T... values) {
		for (T value : values) if (value != null) return value;
		return null;
	}

	record EvidenceBlock(int pageIndex, String elementType, String text, double x, double y,
			double width, double height, int documentOrder, Double confidence,
			boolean hasBounds, String sourcePath, String regionPath) {
		BoundingBox box() {
			return hasBounds ? new BoundingBox(x, y, width, height) : null;
		}
	}

	record DateParts(LocalDate date, String monthDay, DateSource source) {
		static final DateParts EMPTY = new DateParts(null, null, DateSource.MISSING);
		static final DateParts CONFLICT = new DateParts(null, null, DateSource.CONFLICT);

		static DateParts of(LocalDate date) {
			return date == null ? EMPTY : new DateParts(date,
					"%02d-%02d".formatted(date.getMonthValue(), date.getDayOfMonth()),
					DateSource.EXPLICIT_FULL_DATE);
		}

		boolean hasValue() {
			return date != null || monthDay != null;
		}

		String publicValue() {
			return date == null ? monthDay : date.toString();
		}
	}

	private record DateMerge(LocalDate date, String monthDay, DateSource source) {
		private static final DateMerge CONFLICT = new DateMerge(null, null, DateSource.CONFLICT);
	}

	private record EvidenceContext(int pageIndex, String elementType, BoundingBox box,
			Double confidence, String regionPath) {
	}

	private record EvidenceNormalization(List<EvidenceBlock> blocks, int discardedEvidenceCount) {
	}

	private record PositionedToken<T>(EvidenceBlock block, int characterOffset, T value,
			double confidence, String reasonCode, String evidenceReference) {
		private PositionedToken(EvidenceBlock block, int characterOffset, T value, double confidence) {
			this(block, characterOffset, value, confidence, "SPATIAL_TOKEN",
					"element " + block.documentOrder());
		}
	}

	private record SegmentAnchor(String flightNumber, PositionedToken<String> token) {
	}

	private record TokenRoute(PositionedToken<String> departure, PositionedToken<String> arrival) {
	}

	private enum CandidateSource {
		UNDERSTAND, STRUCTURE
	}

	private record SegmentCandidate(DocumentFlightSegment segment, String flightNumber,
			int documentOrder, CandidateSource source, Map<String, String> fieldSources) {
	}

	private record ParsedPass(BoardingPassExtractionResponse response, List<SegmentCandidate> candidates) {
	}

	record SpatialPass(List<EvidenceBlock> blocks, List<SegmentCandidate> candidates,
			int distinctFlightCount, int pageCount, int discardedEvidenceCount,
			int duplicateFlightEvidenceCount) {
	}

	private record ProviderAttempt(JsonNode response, RuntimeException failure) {
	}

	private record FlightAssessment(String flightNumber, FlightNumberConfidence confidence,
			boolean requiresConfirmation, String reasonCode) {
		private static final FlightAssessment LOW = new FlightAssessment(null,
				FlightNumberConfidence.LOW, false, "UNSUPPORTED_FLIGHT_NUMBER");
	}

	private record FlightMerge(String flightNumber, FlightAssessment assessment) {
	}

	private record MergeOutcome(List<DocumentFlightSegment> segments, List<Set<String>> conflicts) {
	}

	private static final class MergedEntry {
		private DocumentFlightSegment segment;
		private final int documentOrder;
		private final CandidateSource source;
		private final Set<String> conflicts;

		private MergedEntry(DocumentFlightSegment segment, int documentOrder,
				CandidateSource source, Set<String> conflicts) {
			this.segment = segment;
			this.documentOrder = documentOrder;
			this.source = source;
			this.conflicts = conflicts;
		}
	}
}
