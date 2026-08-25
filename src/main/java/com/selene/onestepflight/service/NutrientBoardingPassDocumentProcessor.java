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
import java.util.LinkedHashSet;
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
	private static final Pattern FLIGHT_NUMBER_PATTERN = Pattern.compile(
			"^(?:[A-Z][A-Z0-9]|[0-9][A-Z]|[A-Z][A-Z0-9]{2}|[0-9][A-Z][A-Z0-9]|[0-9]{2}[A-Z])\\d{1,4}[A-Z]?$");
	private static final Pattern EXPLICIT_IATA_IN_TEXT = Pattern.compile("(?<![A-Za-z])([A-Z]{3})(?![A-Za-z])");
	private static final List<String> SEGMENT_FIELD_NAMES = List.of(
			"flight_number", "departure_date", "departure_airport", "arrival_airport",
			"scheduled_departure", "scheduled_arrival", "boarding_time", "terminal", "gate", "seat");
	private static final List<String> RECOVERY_TARGET_FIELD_NAMES = List.of(
			"flight_number", "departure_date", "departure_airport", "arrival_airport",
			"scheduled_departure", "scheduled_arrival");
	private static final Set<String> REQUIRED_IDENTITY_FIELDS = Set.of(
			"flight_number", "departure_date", "departure_airport", "arrival_airport");
	private static final Map<String, String> AIRPORT_NAME_TO_IATA = Map.ofEntries(
			Map.entry("MANCHESTER AIRPORT", "MAN"),
			Map.entry("MANCHESTER", "MAN"),
			Map.entry("LONDON HEATHROW", "LHR"),
			Map.entry("HEATHROW", "LHR"),
			Map.entry("CHARLES DE GAULLE", "CDG"),
			Map.entry("戴高乐", "CDG"),
			Map.entry("AMSTERDAM SCHIPHOL", "AMS"),
			Map.entry("SCHIPHOL", "AMS"),
			Map.entry("XIAMEN GAOQI", "XMN"),
			Map.entry("GAOQI", "XMN"),
			Map.entry("高崎", "XMN"));
	private static final Pattern FULL_CHINESE_DATE = Pattern.compile(
			"^(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?$");
	private static final Pattern SLASH_FULL_DATE = Pattern.compile("^(\\d{4})/(\\d{1,2})/(\\d{1,2})$");

	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final ExtractionRequester extractionRequester;

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
		this.objectMapper = objectMapper;
		this.clock = Objects.requireNonNull(clock, "clock");
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
		// Keep connection (8 s) plus response read within the 60-second extraction budget.
		requestFactory.setReadTimeout(Duration.ofSeconds(52));
		// Nutrient's extraction endpoint currently resets chunked multipart uploads.
		// Buffering is bounded by the controller's 10 MB upload limit and gives the
		// JDK client an exact Content-Length without changing TLS verification.
		this.restClient = RestClient.builder()
				.requestFactory(new BufferingClientHttpRequestFactory(requestFactory))
				.build();
		this.extractionRequester = this::requestExtraction;
	}

	@Override
	public BoardingPassExtractionResponse extract(byte[] documentBytes, String filename, String contentType) {
		try {
			JsonNode firstPassNode = extractionRequester.request(documentBytes, filename, contentType,
					"first-pass", extractionInstructions(documentSchema()));
			ParsedPass firstPass = parsePass(firstPassNode);
			CoverageAudit initialAudit = coverageAudit(firstPass.response(), firstPass.flightNumberCandidates(),
					firstPass.visibleSegmentCount());
			if (!initialAudit.recoveryRequired()) {
				logExtractionOutcome(firstPass.response(), initialAudit,
						firstPass.response().segments().size(), false, 0, 0);
				return firstPass.response();
			}

			try {
				JsonNode evidenceNode = extractionRequester.request(documentBytes, filename, contentType,
						"raw-evidence", extractionInstructions(recoveryEvidenceSchema()));
				RecoveryEvidence recoveryEvidence = parseRecoveryEvidence(evidenceNode);
				CoverageAudit recoveryAudit = coverageAudit(firstPass.response(),
						mergeCandidates(firstPass.flightNumberCandidates(), recoveryEvidence.flightNumberCandidates()),
						Math.max(firstPass.visibleSegmentCount(), recoveryEvidence.visibleSegmentCount()));
				if (!hasRecoveryTarget(recoveryEvidence)) {
					logExtractionOutcome(firstPass.response(), recoveryAudit,
							firstPass.response().segments().size(), true, 0, 0);
					return firstPass.response();
				}

				JsonNode focusedNode = extractionRequester.request(documentBytes, filename, contentType,
						"focused-pass", extractionInstructions(focusedRecoverySchema(
								firstPass.response(), recoveryAudit, recoveryEvidence)));
				List<IndexedSegment> focusedSegments = parseIndexedSegments(focusedNode,
						"Nutrient focused citation");
				boolean allowSegmentInsertion = recoveryAudit.reasons().contains("SEGMENT_COUNT_MISMATCH")
						|| recoveryAudit.reasons().contains("NO_SEGMENTS");
				List<IndexedSegment> corroboratedFocusedSegments = focusedSegments.stream()
						.map(segment -> corroborateFocusedSegment(segment, firstPass.response().segments(),
								recoveryEvidence, allowSegmentInsertion))
						.filter(Objects::nonNull)
						.sorted((left, right) -> Integer.compare(left.documentOrder(), right.documentOrder()))
						.toList();
				MergeOutcome focusedMerge = mergeSegments(firstPass.response().segments(),
						corroboratedFocusedSegments,
						allowSegmentInsertion);
				BoardingPassExtractionResponse result = recoveredResponse(
						firstPass.response(), focusedMerge.segments(), focusedMerge.conflicts());
				logExtractionOutcome(result, recoveryAudit, firstPass.response().segments().size(), true,
						recoveryEvidence.flightNumberCandidates().size(), 1);
				return result;
			} catch (RestClientException | JacksonException | ExternalServiceUnavailableException exception) {
				LOGGER.warn("provider=NUTRIENT stage=document-recovery outcome=FIRST_PASS_RETAINED rootCauseType={}",
						exception.getClass().getName());
				logExtractionOutcome(firstPass.response(), initialAudit,
						firstPass.response().segments().size(), true, 0, 0);
				return firstPass.response();
			}
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

	}

	private JsonNode requestExtraction(byte[] documentBytes, String filename, String contentType,
			String stage, Map<String, Object> instructions)
			throws JacksonException {
		String apiKey = System.getenv("NUTRIENT_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			throw new ExternalServiceNotConfiguredException("NUTRIENT");
		}
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", filePart(documentBytes, filename, contentType));
		body.add("instructions", instructionsPart(instructions));
		ResponseEntity<JsonNode> response = restClient.post()
				.uri(EXTRACT_ENDPOINT)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(body)
				.retrieve()
				.toEntity(JsonNode.class);
		LOGGER.info("provider=NUTRIENT stage={} httpStatus={} contentType={}",
				stage, response.getStatusCode().value(), response.getHeaders().getContentType());
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

	private Map<String, Object> extractionInstructions(Map<String, Object> schema) {
		Map<String, Object> instructions = new LinkedHashMap<>();
		instructions.put("mode", "understand");
		instructions.put("schema", schema);
		instructions.put("citationsEnabled", true);
		return instructions;
	}

	Map<String, Object> documentSchema() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("document_type", enumProperty(
				"Classify the visible document only. Use UNKNOWN for a non-flight document or when the type is unclear."));
		properties.put("segments", segmentArrayProperty());
		properties.put("flight_number_candidates", flightCandidateArrayProperty(
				"Every clearly visible airline flight designator tied to a flight row, route, or departure time, in document order. Preserve visible spacing such as MF 825. Exclude ticket numbers, booking references, passenger data, baggage allowances, dates, times, and standalone numbers. Return an empty array when no strict flight-number evidence is visible."));
		properties.put("visible_flight_segment_count", integerProperty(
				"Count only distinct visible flight legs supported by a flight designator plus nearby route or time context. Do not count passenger, ticket, passport, booking, baggage, date, or time rows."));
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("description", "Extract every distinct flight leg into segments in the same order as the document. Preserve visible evidence even when it is not already normalized. Do not silently choose the first leg and do not guess missing values. A departure date visibly shared by a heading or table section may be copied unchanged only to the segments clearly governed by that heading or section; never carry it across a different group, page, or unrelated segment. Never extract or return passenger names, PNRs, booking references, ticket numbers, passport data, or barcode contents.");
		schema.put("properties", properties);
		// Non-flight and unclear files return UNKNOWN with an empty segments array;
		// no flight identity is required or invented.
		schema.put("required", List.of("document_type", "segments", "flight_number_candidates",
				"visible_flight_segment_count"));
		return schema;
	}

	private Map<String, Object> segmentArrayProperty() {
		Map<String, Object> segmentProperties = new LinkedHashMap<>();
		segmentProperties.put("flight_number", stringProperty("Copy the visible operating or marketed flight designator, including harmless spacing such as MF 825. Never correct ambiguous characters and never return a booking reference, PNR, passenger name, ticket number, or barcode data."));
		segmentProperties.put("departure_date", stringProperty("Copy the visible local departure date without inventing a year. Preserve visible text when it is not already ISO. A date printed once in a shared heading may be repeated only for the segments visibly governed by that heading."));
		segmentProperties.put("departure_airport", stringProperty("Prefer a clearly visible three-letter IATA code. If only an airport name is visible, copy that visible name unchanged instead of omitting it or guessing a code."));
		segmentProperties.put("arrival_airport", stringProperty("Prefer a clearly visible three-letter IATA code. If only an airport name is visible, copy that visible name unchanged instead of omitting it or guessing a code."));
		segmentProperties.put("scheduled_departure", stringProperty("Copy the visible scheduled local departure date and time. Use ISO YYYY-MM-DDTHH:mm only when the visible date and time support it; otherwise preserve the visible text and do not invent missing parts."));
		segmentProperties.put("scheduled_arrival", stringProperty("Copy the visible scheduled local arrival date and time. If the document explicitly marks arrival as next day or +1, preserve that visible meaning; never change departure_date and never infer a rollover without explicit evidence."));
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

	private Map<String, Object> flightCandidateArrayProperty(String description) {
		Map<String, Object> candidate = new LinkedHashMap<>();
		candidate.put("type", "object");
		candidate.put("properties", Map.of(
				"value", stringProperty("The visible flight designator only."),
				"document_order", integerProperty("Zero-based order among visible flight legs."),
				"context", Map.of("type", "string", "enum",
						List.of("ROUTE", "DEPARTURE_TIME", "FLIGHT_LABEL"),
						"description", "Why this candidate is a flight designator rather than an unrelated number.")));
		candidate.put("required", List.of("value", "document_order", "context"));
		return Map.of("type", "array", "items", candidate, "description", description);
	}

	private Map<String, Object> integerProperty(String description) {
		return Map.of("type", "integer", "description", description);
	}

	private Map<String, Object> enumProperty(String description) {
		return Map.of(
				"type", "string",
				"enum", List.of("BOARDING_PASS", "ITINERARY", "E_TICKET", "BOOKING_CONFIRMATION", "UNKNOWN"),
				"description", description);
	}

	private Map<String, Object> recoveryEvidenceSchema() {
		Map<String, Object> evidenceFields = new LinkedHashMap<>();
		evidenceFields.put("document_order", integerProperty(
				"Zero-based position of this flight leg in the document."));
		for (String fieldName : SEGMENT_FIELD_NAMES) {
			evidenceFields.put(fieldName + "_raw", stringProperty(
					"Copy only the visible " + fieldName.replace('_', ' ')
							+ " text for this flight leg. Do not normalize, infer, or include surrounding passenger or booking text."));
		}
		Map<String, Object> evidenceItem = new LinkedHashMap<>();
		evidenceItem.put("type", "object");
		evidenceItem.put("properties", evidenceFields);
		evidenceItem.put("required", List.of("document_order"));

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("flight_number_candidates", flightCandidateArrayProperty(
				"Strict visible airline flight designators with route, departure-time, or explicit flight-label context, in document order. Exclude all passenger, booking, ticket, passport, barcode, baggage, date, time, and standalone-number values."));
		properties.put("visible_flight_segment_count", integerProperty(
				"Count only distinct visible flight legs with strict flight context."));
		properties.put("evidence_segments", Map.of(
				"type", "array",
				"items", evidenceItem,
				"description", "Flight-only raw evidence groups in document order. This is evidence for a later focused pass, not a request to invent normalized itinerary data."));

		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("description", "Recheck only visible flight-leg evidence after an incomplete structured pass. Preserve raw flight-related text and its document order. Never return full document text, passenger names, PNRs, booking references, ticket or passport numbers, barcode contents, or unrelated numbers.");
		schema.put("properties", properties);
		schema.put("required", List.of("flight_number_candidates", "visible_flight_segment_count",
				"evidence_segments"));
		return schema;
	}

	private Map<String, Object> focusedRecoverySchema(BoardingPassExtractionResponse firstPass,
			CoverageAudit audit, RecoveryEvidence evidence) {
		LinkedHashSet<String> requestedFields = new LinkedHashSet<>();
		for (DocumentFlightSegment segment : firstPass.segments()) {
			if (segment.flightNumber() == null) requestedFields.add("flight_number");
			if (segment.departureDate() == null) {
				requestedFields.add("departure_date");
				requestedFields.add("scheduled_departure");
			}
		}
		if (audit.reasons().contains("SEGMENT_COUNT_MISMATCH")
				|| audit.reasons().contains("NO_SEGMENTS")) {
			requestedFields.addAll(List.of("flight_number", "departure_date", "departure_airport",
					"arrival_airport", "scheduled_departure", "scheduled_arrival"));
		}
		if (audit.reasons().contains("LOW_CONFIDENCE_IDENTITY")) {
			requestedFields.addAll(List.of("flight_number", "departure_date", "scheduled_departure"));
		}
		if (requestedFields.isEmpty()) requestedFields.add("flight_number");

		Map<String, Object> focusedFields = new LinkedHashMap<>();
		focusedFields.put("document_order", integerProperty(
				"Zero-based position of this flight leg in the original document."));
		for (String fieldName : requestedFields) {
			focusedFields.put(fieldName, focusedFieldProperty(fieldName));
		}
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("type", "object");
		item.put("properties", focusedFields);
		item.put("required", List.of("document_order"));

		String candidates = evidence.flightNumberCandidates().isEmpty() ? "none"
				: String.join(", ", evidence.flightNumberCandidates());
		String evidenceAnchors = evidence.segments().stream()
				.map(this::safeEvidenceAnchor)
				.filter(value -> !value.isBlank())
				.reduce((left, right) -> left + "; " + right).orElse("none");
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("description", "Repair only the missing flight fields or omitted flight legs identified by the first pass. Use only evidence visible in the original document. Recovery reasons: "
				+ String.join(",", audit.reasons()) + ". Strict evidence candidates: " + candidates
				+ ". Flight-only position anchors: " + evidenceAnchors
				+ ". Return only corroborated target fields and their original document_order. Do not regenerate unrelated complete fields, infer missing values, alter document order, or return passenger, booking, ticket, passport, or barcode data.");
		schema.put("properties", Map.of("segments", Map.of(
				"type", "array", "items", item,
				"description", "Only targeted missing segments or fields, in original document order.")));
		schema.put("required", List.of("segments"));
		return schema;
	}

	private Map<String, String> focusedFieldProperty(String fieldName) {
		return stringProperty(switch (fieldName) {
			case "flight_number" -> "Visible airline flight designator for the targeted leg. Preserve harmless spacing; do not correct ambiguous characters.";
			case "departure_date" -> "Visible local departure date for the targeted leg. Do not infer a year or use arrival time.";
			case "departure_airport", "arrival_airport" -> "Visible IATA code or airport name for the targeted leg only.";
			case "scheduled_departure" -> "Visible local scheduled departure date and time for the targeted leg.";
			case "scheduled_arrival" -> "Visible local scheduled arrival date and time for the targeted leg.";
			default -> "Visible value for the targeted flight leg only.";
		});
	}

	private String safeEvidenceAnchor(IndexedSegment indexed) {
		DocumentFlightSegment segment = indexed.segment();
		List<String> anchors = new ArrayList<>();
		anchors.add("index=" + indexed.documentOrder());
		if (segment.flightNumber() != null) anchors.add("flight=" + segment.flightNumber());
		if (segment.departureAirport() != null || segment.arrivalAirport() != null) {
			anchors.add("route=" + Objects.toString(segment.departureAirport(), "?") + "-"
					+ Objects.toString(segment.arrivalAirport(), "?"));
		}
		return String.join(" ", anchors);
	}

	BoardingPassExtractionResponse toResponse(JsonNode response) {
		return parsePass(response).response();
	}

	private ParsedPass parsePass(JsonNode response) {
		if (response == null || !response.path("output").path("data").isObject()) {
			throw new ExternalServiceUnavailableException("NUTRIENT_RESPONSE_INVALID");
		}

		JsonNode data = response.path("output").path("data");
		JsonNode metadata = response.path("output").path("metadata");
		Map<String, ExtractedField> fields = new LinkedHashMap<>();
		ExtractedField rawDocumentType = extractedField(data, metadata, "document_type");
		DocumentType documentType = DocumentType.fromProviderValue(rawDocumentType.value());
		fields.put("document_type", new ExtractedField(documentType.name(), rawDocumentType.rawValue(),
				rawDocumentType.confidence(), rawDocumentType.page(), rawDocumentType.boundingBox(),
				rawDocumentType.sourceLabel()));

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
		BoardingPassExtractionResponse parsed = new BoardingPassExtractionResponse(
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
		List<String> candidates = flightNumberCandidates(data.path("flight_number_candidates"));
		int visibleSegmentCount = data.path("visible_flight_segment_count").canConvertToInt()
				? Math.max(0, data.path("visible_flight_segment_count").asInt()) : candidates.size();
		return new ParsedPass(parsed, candidates, visibleSegmentCount);
	}

	private RecoveryEvidence parseRecoveryEvidence(JsonNode response) {
		JsonNode data = response == null ? null : response.path("output").path("data");
		if (data == null || !data.isObject()) {
			throw new ExternalServiceUnavailableException("NUTRIENT_RESPONSE_INVALID");
		}
		JsonNode metadata = response.path("output").path("metadata");
		List<IndexedSegment> evidenceSegments = new ArrayList<>();
		JsonNode segmentData = data.path("evidence_segments");
		for (int index = 0; segmentData.isArray() && index < segmentData.size(); index++) {
			JsonNode rawSegment = segmentData.path(index);
			int documentOrder = rawSegment.path("document_order").canConvertToInt()
					? Math.max(0, rawSegment.path("document_order").asInt()) : index;
			evidenceSegments.add(new IndexedSegment(documentOrder,
					parseEvidenceSegment(rawSegment, indexedMetadata(metadata.path("evidence_segments"), index),
							index)));
		}
		List<FlightCandidate> orderedCandidates = new ArrayList<>(
				orderedFlightNumberCandidates(data.path("flight_number_candidates")));
		for (IndexedSegment evidenceSegment : evidenceSegments) {
			String flightNumber = evidenceSegment.segment().flightNumber();
			if (flightNumber != null && orderedCandidates.stream().noneMatch(candidate ->
					candidate.documentOrder() == evidenceSegment.documentOrder()
							&& candidate.value().equals(flightNumber))) {
				orderedCandidates.add(new FlightCandidate(flightNumber, evidenceSegment.documentOrder()));
			}
		}
		List<String> candidates = mergeCandidates(orderedCandidates.stream().map(FlightCandidate::value).toList(),
				evidenceSegments.stream().map(IndexedSegment::segment)
						.map(DocumentFlightSegment::flightNumber).filter(Objects::nonNull).toList());
		int visibleSegmentCount = data.path("visible_flight_segment_count").canConvertToInt()
				? Math.max(0, data.path("visible_flight_segment_count").asInt()) : candidates.size();
		return new RecoveryEvidence(candidates, visibleSegmentCount, List.copyOf(evidenceSegments),
				List.copyOf(orderedCandidates));
	}

	private boolean hasRecoveryTarget(RecoveryEvidence evidence) {
		if (evidence == null) return false;
		if (evidence.visibleSegmentCount() > 0 || !evidence.flightNumberCandidates().isEmpty()) return true;
		return evidence.segments().stream()
				.map(IndexedSegment::segment)
				.filter(Objects::nonNull)
				.flatMap(segment -> RECOVERY_TARGET_FIELD_NAMES.stream().map(segment.fields()::get))
				.anyMatch(field -> field != null && field.rawValue() != null);
	}

	private IndexedSegment corroborateFocusedSegment(IndexedSegment focused,
			List<DocumentFlightSegment> firstPassSegments, RecoveryEvidence evidence,
			boolean allowSegmentInsertion) {
		if (focused == null || focused.segment() == null) return null;
		int documentOrder = focused.documentOrder();
		DocumentFlightSegment existing = documentOrder >= 0 && documentOrder < firstPassSegments.size()
				? firstPassSegments.get(documentOrder) : null;
		DocumentFlightSegment rawEvidence = evidence.segments().stream()
				.filter(item -> item.documentOrder() == documentOrder)
				.map(IndexedSegment::segment)
				.findFirst().orElse(null);

		Map<String, ExtractedField> corroboratedFields = new LinkedHashMap<>();
		for (String name : SEGMENT_FIELD_NAMES) {
			ExtractedField field = focused.segment().fields().get(name);
			if (field == null) continue;
			boolean rawSupported = field.value() != null && field.confidence() >= 0.8d
					&& focusedValueSupported(name, field.value(), rawEvidence, evidence, documentOrder);
			boolean comparesWithExisting = !allowSegmentInsertion && semanticValue(existing, name) != null;
			boolean retainedForMerge = rawSupported || comparesWithExisting;
			ExtractedField retained = rawSupported ? field
					: new ExtractedField(field.value(), field.rawValue(), 0.0d, field.page(),
							field.boundingBox(), field.sourceLabel());
			corroboratedFields.put(name, retainedForMerge ? retained
					: new ExtractedField(null, field.rawValue(), field.confidence(), field.page(),
							field.boundingBox(), field.sourceLabel()));
		}
		for (String name : SEGMENT_FIELD_NAMES) {
			corroboratedFields.putIfAbsent(name,
					new ExtractedField(null, null, 0.0d, null, null, null));
		}
		DocumentFlightSegment corroborated = segmentFromFields(corroboratedFields);
		if (!hasNormalizedSegmentEvidence(corroborated)) return null;
		return new IndexedSegment(documentOrder, corroborated);
	}

	private boolean focusedValueSupported(String name, String focusedValue,
			DocumentFlightSegment rawEvidence, RecoveryEvidence evidence, int documentOrder) {
		ExtractedField evidenceField = rawEvidence == null ? null : rawEvidence.fields().get(name);
		if ("flight_number".equals(name)) {
			LinkedHashSet<String> candidatesAtOrder = new LinkedHashSet<>();
			evidence.orderedFlightCandidates().stream()
					.filter(candidate -> candidate.documentOrder() == documentOrder)
					.map(FlightCandidate::value)
					.forEach(candidatesAtOrder::add);
			if (evidenceField != null && evidenceField.value() != null) {
				candidatesAtOrder.add(evidenceField.value());
			}
			return candidatesAtOrder.size() == 1 && candidatesAtOrder.contains(focusedValue);
		}
		if (evidenceField != null && evidenceField.rawValue() != null) {
			if (evidenceField.value() != null) return focusedValue.equals(evidenceField.value());
		}
		if ("departure_date".equals(name) && rawEvidence != null) {
			ExtractedField scheduled = rawEvidence.fields().get("scheduled_departure");
			LocalDate scheduledDate = scheduled == null ? null : localDateFromDateTime(scheduled.value());
			return scheduledDate != null && focusedValue.equals(scheduledDate.toString());
		}
		return false;
	}

	private String semanticValue(DocumentFlightSegment segment, String name) {
		if (segment == null) return null;
		return switch (name) {
			case "flight_number" -> segment.flightNumber();
			case "departure_date" -> segment.departureDate() == null ? null : segment.departureDate().toString();
			case "departure_airport" -> segment.departureAirport();
			case "arrival_airport" -> segment.arrivalAirport();
			case "scheduled_departure" -> segment.scheduledDeparture();
			case "scheduled_arrival" -> segment.scheduledArrival();
			case "boarding_time" -> segment.boardingTime();
			case "terminal" -> segment.terminal();
			case "gate" -> segment.gate();
			case "seat" -> segment.seat();
			default -> null;
		};
	}

	private boolean hasNormalizedSegmentEvidence(DocumentFlightSegment segment) {
		return segment != null && segment.fields().values().stream()
				.anyMatch(field -> field != null && field.value() != null);
	}

	private DocumentFlightSegment parseEvidenceSegment(JsonNode data, JsonNode metadata, int index) {
		Map<String, ExtractedField> fields = new LinkedHashMap<>();
		for (String fieldName : SEGMENT_FIELD_NAMES) {
			String rawName = fieldName + "_raw";
			ExtractedField extracted = extractedField(data, metadata, rawName,
					"evidence_segments[" + index + "]." + rawName);
			double confidence = extracted.rawValue() == null ? 0.0d
					: extracted.confidence() > 0.0d ? extracted.confidence() : 0.55d;
			ExtractedField raw = new ExtractedField(extracted.rawValue(), extracted.rawValue(), confidence,
					extracted.page(), extracted.boundingBox(), extracted.sourceLabel());
			fields.put(fieldName, sanitizeField(fieldName, raw));
		}
		return segmentFromFields(fields);
	}

	private List<IndexedSegment> parseIndexedSegments(JsonNode response, String sourceLabel) {
		JsonNode data = response == null ? null : response.path("output").path("data");
		if (data == null || !data.isObject()) {
			throw new ExternalServiceUnavailableException("NUTRIENT_RESPONSE_INVALID");
		}
		JsonNode metadata = response.path("output").path("metadata");
		JsonNode segmentData = data.path("segments");
		List<IndexedSegment> segments = new ArrayList<>();
		for (int index = 0; segmentData.isArray() && index < segmentData.size(); index++) {
			JsonNode item = segmentData.path(index);
			List<String> ignoredReview = new ArrayList<>();
			DocumentFlightSegment segment = parseSegment(item, segmentMetadata(metadata, index),
					index, ignoredReview);
			segment = withEvidenceSource(segment, sourceLabel, index);
			int documentOrder = item.path("document_order").canConvertToInt()
					? Math.max(0, item.path("document_order").asInt()) : index;
			segments.add(new IndexedSegment(documentOrder, segment));
		}
		return List.copyOf(segments);
	}

	private List<String> flightNumberCandidates(JsonNode candidatesNode) {
		return orderedFlightNumberCandidates(candidatesNode).stream().map(FlightCandidate::value).toList();
	}

	private List<FlightCandidate> orderedFlightNumberCandidates(JsonNode candidatesNode) {
		List<FlightCandidate> candidates = new ArrayList<>();
		if (candidatesNode != null && candidatesNode.isArray()) {
			for (JsonNode candidate : candidatesNode) {
				if (!candidate.isObject()) continue;
				String context = valueOrNull(candidate.path("context"));
				if (!List.of("ROUTE", "DEPARTURE_TIME", "FLIGHT_LABEL").contains(context)) continue;
				String normalized = normalizeFlightNumber(valueOrNull(candidate.path("value")));
				int documentOrder = candidate.path("document_order").canConvertToInt()
						? Math.max(0, candidate.path("document_order").asInt()) : candidates.size();
				if (normalized != null) candidates.add(new FlightCandidate(normalized, documentOrder));
			}
		}
		return List.copyOf(candidates);
	}

	private List<String> mergeCandidates(List<String> first, List<String> second) {
		List<String> firstSafe = first == null ? List.of() : first;
		List<String> secondSafe = second == null ? List.of() : second;
		return List.copyOf(secondSafe.size() > firstSafe.size() ? secondSafe : firstSafe);
	}

	private CoverageAudit coverageAudit(BoardingPassExtractionResponse response,
			List<String> flightCandidates, int visibleSegmentCount) {
		LinkedHashSet<String> reasons = new LinkedHashSet<>();
		List<DocumentFlightSegment> segments = response == null ? List.of() : response.segments();
		if (segments.isEmpty()) {
			reasons.add("NO_SEGMENTS");
		}
		int usableCount = 0;
		List<String> structuredFlights = new ArrayList<>();
		for (DocumentFlightSegment segment : segments) {
			if (segment.flightNumber() == null) reasons.add("MISSING_FLIGHT_NUMBER");
			else structuredFlights.add(segment.flightNumber());
			if (segment.departureDate() == null) reasons.add("MISSING_DEPARTURE_DATE");
			if (segment.flightNumber() != null && segment.departureDate() != null) usableCount++;

			ExtractedField flightEvidence = segment.fields().get("flight_number");
			if (segment.flightNumber() != null && (flightEvidence == null
					|| flightEvidence.confidence() < 0.8d)) reasons.add("LOW_CONFIDENCE_IDENTITY");
			ExtractedField dateEvidence = segment.fields().get("departure_date");
			if (segment.departureDate() != null && (dateEvidence == null || dateEvidence.value() == null)) {
				dateEvidence = segment.fields().get("scheduled_departure");
			}
			if (segment.departureDate() != null && dateEvidence != null && dateEvidence.value() != null
					&& dateEvidence.confidence() < 0.8d) reasons.add("LOW_CONFIDENCE_IDENTITY");
		}
		List<String> candidates = flightCandidates == null ? List.of() : flightCandidates;
		if (visibleSegmentCount > segments.size() || candidates.size() > segments.size()) {
			reasons.add("SEGMENT_COUNT_MISMATCH");
		}
		if (!candidates.isEmpty() && !candidates.equals(structuredFlights)) {
			reasons.add("SEGMENT_IDENTITY_MISMATCH");
		}
		return new CoverageAudit(!reasons.isEmpty(), List.copyOf(reasons), usableCount);
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
		LocalDate departureDate = parseDate(segmentFields.get("departure_date").value());
		if (departureDate == null) {
			departureDate = localDateFromDateTime(segmentFields.get("scheduled_departure").value());
			if (departureDate != null) {
				needsReview.remove("segments[" + index + "].departure_date");
			}
		}
		return new DocumentFlightSegment(
				normalizeFlightNumber(segmentFields.get("flight_number").value()),
				departureDate,
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
		String rawValue = field.rawValue();
		String value = switch (name) {
			case "flight_number" -> normalizeFlightNumber(rawValue);
			case "departure_date" -> parseDate(rawValue) == null ? null : parseDate(rawValue).toString();
			case "departure_airport", "arrival_airport" -> normalizeAirport(rawValue);
			case "scheduled_departure", "scheduled_arrival" -> validDateTime(rawValue);
			case "boarding_time" -> validBoardingTime(rawValue);
			case "terminal", "gate", "seat" -> safeShortValue(rawValue);
			default -> null;
		};
		return new ExtractedField(value, rawValue, field.confidence(), field.page(),
				field.boundingBox(), field.sourceLabel());
	}

	private DocumentFlightSegment segmentFromFields(Map<String, ExtractedField> fields) {
		LocalDate departureDate = parseDate(fields.get("departure_date").value());
		if (departureDate == null) {
			departureDate = localDateFromDateTime(fields.get("scheduled_departure").value());
		}
		ExtractedField identity = fields.get("flight_number");
		return new DocumentFlightSegment(
				fields.get("flight_number").value(), departureDate,
				fields.get("departure_airport").value(), fields.get("arrival_airport").value(),
				fields.get("scheduled_departure").value(), fields.get("scheduled_arrival").value(),
				fields.get("boarding_time").value(), fields.get("terminal").value(),
				fields.get("gate").value(), fields.get("seat").value(),
				segmentConfidence(fields), identity.page(), identity.boundingBox(), fields);
	}

	private DocumentFlightSegment withEvidenceSource(DocumentFlightSegment segment,
			String sourceLabel, int index) {
		Map<String, ExtractedField> fields = new LinkedHashMap<>();
		segment.fields().forEach((name, field) -> {
			fields.put(name, new ExtractedField(field.value(), field.rawValue(), field.confidence(),
					field.page(), field.boundingBox(), sourceLabel + ": segments[" + index + "]." + name));
		});
		return rebuildSegment(segment, fields, segment.confidence());
	}

	private MergeOutcome mergeSegments(List<DocumentFlightSegment> original,
			List<IndexedSegment> recovered, boolean allowSegmentInsertion) {
		List<DocumentFlightSegment> merged = new ArrayList<>(original == null ? List.of() : original);
		LinkedHashSet<String> conflicts = new LinkedHashSet<>();
		for (IndexedSegment candidate : recovered) {
			DocumentFlightSegment incoming = candidate.segment();
			if (incoming == null || !hasAnySegmentEvidence(incoming)) continue;
			int requestedOrder = Math.min(Math.max(0, candidate.documentOrder()), merged.size());
			int target = -1;
			if (requestedOrder < merged.size()
					&& (!allowSegmentInsertion || canOverlay(merged.get(requestedOrder), incoming))) {
				target = requestedOrder;
			}
			if (target < 0 && !allowSegmentInsertion) {
				target = matchingFlightIndex(merged, incoming.flightNumber());
			}
			if (target < 0) {
				if (allowSegmentInsertion) merged.add(requestedOrder, incoming);
				continue;
			}
			merged.set(target, mergeSegment(merged.get(target), incoming, target, conflicts));
		}
		return new MergeOutcome(List.copyOf(merged), List.copyOf(conflicts));
	}

	private boolean hasAnySegmentEvidence(DocumentFlightSegment segment) {
		return segment.flightNumber() != null || segment.departureDate() != null
				|| segment.departureAirport() != null || segment.arrivalAirport() != null
				|| segment.fields().values().stream().anyMatch(field -> field.value() != null);
	}

	private int matchingFlightIndex(List<DocumentFlightSegment> segments, String flightNumber) {
		if (flightNumber == null) return -1;
		for (int index = 0; index < segments.size(); index++) {
			if (flightNumber.equals(segments.get(index).flightNumber())) return index;
		}
		return -1;
	}

	private boolean canOverlay(DocumentFlightSegment existing, DocumentFlightSegment incoming) {
		if (existing.flightNumber() == null && incoming.flightNumber() != null) return true;
		if (existing.flightNumber() != null && incoming.flightNumber() != null
				&& !existing.flightNumber().equals(incoming.flightNumber())) return false;
		if (existing.departureDate() != null && incoming.departureDate() != null
				&& !existing.departureDate().equals(incoming.departureDate())) return false;
		if (existing.departureAirport() != null && incoming.departureAirport() != null
				&& !existing.departureAirport().equals(incoming.departureAirport())) return false;
		return existing.arrivalAirport() == null || incoming.arrivalAirport() == null
				|| existing.arrivalAirport().equals(incoming.arrivalAirport());
	}

	private DocumentFlightSegment mergeSegment(DocumentFlightSegment first, DocumentFlightSegment second,
			int index, Set<String> conflicts) {
		String flightNumber = mergeValue(first.flightNumber(), second.flightNumber(),
				"flight_number", index, conflicts, first, second);
		LocalDate departureDate = mergeValue(first.departureDate(), second.departureDate(),
				"departure_date", index, conflicts, first, second);
		String departureAirport = mergeValue(first.departureAirport(), second.departureAirport(),
				"departure_airport", index, conflicts, first, second);
		String arrivalAirport = mergeValue(first.arrivalAirport(), second.arrivalAirport(),
				"arrival_airport", index, conflicts, first, second);
		String scheduledDeparture = mergeValue(first.scheduledDeparture(), second.scheduledDeparture(),
				"scheduled_departure", index, conflicts, first, second);
		String scheduledArrival = mergeValue(first.scheduledArrival(), second.scheduledArrival(),
				"scheduled_arrival", index, conflicts, first, second);
		String boardingTime = mergeValue(first.boardingTime(), second.boardingTime(),
				"boarding_time", index, conflicts, first, second);
		String terminal = mergeValue(first.terminal(), second.terminal(),
				"terminal", index, conflicts, first, second);
		String gate = mergeValue(first.gate(), second.gate(), "gate", index, conflicts, first, second);
		String seat = mergeValue(first.seat(), second.seat(), "seat", index, conflicts, first, second);

		Map<String, ExtractedField> fields = mergeEvidence(first.fields(), second.fields());
		return new DocumentFlightSegment(flightNumber, departureDate, departureAirport, arrivalAirport,
				scheduledDeparture, scheduledArrival, boardingTime, terminal, gate, seat,
				Math.max(first.confidence(), second.confidence()),
				first.page() != null ? first.page() : second.page(),
				first.boundingBox() != null ? first.boundingBox() : second.boundingBox(), fields);
	}

	private <T> T mergeValue(T first, T second, String fieldName, int index,
			Set<String> conflicts, DocumentFlightSegment firstSegment,
			DocumentFlightSegment secondSegment) {
		if (first == null) return second;
		if (second == null || Objects.equals(first, second)) return first;
		conflicts.add("segments[" + index + "]." + fieldName);
		ExtractedField firstEvidence = semanticEvidence(firstSegment, fieldName);
		ExtractedField secondEvidence = semanticEvidence(secondSegment, fieldName);
		if (!trustedEvidence(firstEvidence, confidenceThreshold(fieldName))
				&& trustedEvidence(secondEvidence, confidenceThreshold(fieldName))) {
			return second;
		}
		return first;
	}

	private ExtractedField semanticEvidence(DocumentFlightSegment segment, String fieldName) {
		ExtractedField evidence = segment.fields().get(fieldName);
		if ("departure_date".equals(fieldName) && (evidence == null || evidence.value() == null)) {
			return segment.fields().get("scheduled_departure");
		}
		return evidence;
	}

	private double confidenceThreshold(String fieldName) {
		return "flight_number".equals(fieldName) || "departure_date".equals(fieldName) ? 0.8d : 0.65d;
	}

	private Map<String, ExtractedField> mergeEvidence(Map<String, ExtractedField> first,
			Map<String, ExtractedField> second) {
		Map<String, ExtractedField> merged = new LinkedHashMap<>();
		for (String name : SEGMENT_FIELD_NAMES) {
			ExtractedField current = first.get(name);
			ExtractedField recovered = second.get(name);
			double threshold = confidenceThreshold(name);
			if (current == null) {
				merged.put(name, recovered);
			} else if (current.value() == null && recovered != null && recovered.value() != null
					&& trustedEvidence(recovered, threshold)) {
				merged.put(name, recovered);
			} else if (recovered != null && Objects.equals(current.value(), recovered.value())
					&& recovered.confidence() > current.confidence()) {
				merged.put(name, recovered);
			} else if (recovered != null && !Objects.equals(current.value(), recovered.value())
					&& !trustedEvidence(current, threshold) && trustedEvidence(recovered, threshold)) {
				merged.put(name, recovered);
			} else {
				merged.put(name, current);
			}
		}
		return merged;
	}

	private DocumentFlightSegment rebuildSegment(DocumentFlightSegment segment,
			Map<String, ExtractedField> fields, double confidence) {
		return new DocumentFlightSegment(segment.flightNumber(), segment.departureDate(),
				segment.departureAirport(), segment.arrivalAirport(), segment.scheduledDeparture(),
				segment.scheduledArrival(), segment.boardingTime(), segment.terminal(), segment.gate(),
				segment.seat(), confidence, segment.page(), segment.boundingBox(), fields);
	}

	private BoardingPassExtractionResponse recoveredResponse(BoardingPassExtractionResponse first,
			List<DocumentFlightSegment> segments, List<String> conflicts) {
		LinkedHashSet<String> review = new LinkedHashSet<>();
		if (first.needsReviewFields().contains("document_type")) review.add("document_type");
		for (int index = 0; index < segments.size(); index++) {
			addSegmentReviewFields(review, segments.get(index), index);
		}
		review.addAll(conflicts);
		List<String> reviewFields = review.stream().map(field -> segments.size() == 1
				? field.replace("segments[0].", "") : field).toList();
		Map<String, ExtractedField> rootFields = new LinkedHashMap<>(first.fields());
		if (segments.size() == 1) rootFields.putAll(segments.get(0).fields());
		return new BoardingPassExtractionResponse(first.documentType(), null, null, null, null, null,
				null, null, null, null, null, false, false, rootFields, segments, reviewFields,
				"NUTRIENT_DWS_DATA_EXTRACTION", first.extractedAt());
	}

	private void addSegmentReviewFields(Set<String> review, DocumentFlightSegment segment, int index) {
		String prefix = "segments[" + index + "].";
		ExtractedField flight = segment.fields().get("flight_number");
		if (segment.flightNumber() == null || !trustedEvidence(flight, 0.8d)) {
			review.add(prefix + "flight_number");
		}
		ExtractedField date = segment.fields().get("departure_date");
		if (date == null || date.value() == null) date = segment.fields().get("scheduled_departure");
		if (segment.departureDate() == null || !trustedEvidence(date, 0.8d)) {
			review.add(prefix + "departure_date");
		}
		for (String airportField : List.of("departure_airport", "arrival_airport")) {
			if (semanticValue(segment, airportField) == null
					|| !trustedEvidence(segment.fields().get(airportField), 0.65d)) {
				review.add(prefix + airportField);
			}
		}
		for (String optionalField : List.of("scheduled_departure", "scheduled_arrival", "boarding_time",
				"terminal", "gate", "seat")) {
			if (semanticValue(segment, optionalField) != null
					&& !trustedEvidence(segment.fields().get(optionalField), 0.65d)) {
				review.add(prefix + optionalField);
			}
		}
	}

	private boolean trustedEvidence(ExtractedField field, double threshold) {
		return field != null && field.value() != null && field.confidence() >= threshold;
	}

	private void logExtractionOutcome(BoardingPassExtractionResponse result, CoverageAudit audit,
			int firstPassSegmentCount, boolean recoveryTriggered,
			int ocrCandidateFlightCount, int focusedPassCount) {
		LOGGER.info("documentExtraction firstPassSegmentCount={} normalizedUsableCount={} recoveryTriggered={} recoveryReason={} ocrCandidateFlightCount={} focusedPassCount={} finalSegmentCount={} finalUsableCount={}",
				firstPassSegmentCount, audit.normalizedUsableCount(), recoveryTriggered,
				audit.reasons().isEmpty() ? "NONE" : String.join("+", audit.reasons()),
				ocrCandidateFlightCount, focusedPassCount, result.segments().size(),
				result.segments().stream().filter(segment -> segment.flightNumber() != null
						&& segment.departureDate() != null).count());
	}

	private JsonNode segmentMetadata(JsonNode metadata, int index) {
		JsonNode segments = metadata.path("segments");
		if (segments.isArray()) return segments.path(index);
		if (segments.isObject() && !segments.path(Integer.toString(index)).isMissingNode()) {
			return segments.path(Integer.toString(index));
		}
		return metadata;
	}

	private JsonNode indexedMetadata(JsonNode metadata, int index) {
		if (metadata.isArray()) return metadata.path(index);
		if (metadata.isObject() && !metadata.path(Integer.toString(index)).isMissingNode()) {
			return metadata.path(Integer.toString(index));
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
		String rawValue = valueOrNull(data.path(name));
		JsonNode fieldMetadata = metadata.path(name);
		double confidence = fieldMetadata.path("confidence").asDouble(0.0);
		Integer page = fieldMetadata.path("pageIndex").isNumber() ? fieldMetadata.path("pageIndex").asInt() : null;
		BoundingBox box = boundingBox(fieldMetadata.path("bbox"));
		return new ExtractedField(rawValue, rawValue, confidence, page, box,
				"Nutrient citation: " + sourcePath);
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
		return FLIGHT_NUMBER_PATTERN.matcher(normalized).matches() ? normalized : null;
	}

	private String normalizeAirport(String value) {
		if (value == null) return null;
		String visible = Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
		if (visible.matches("(?i)[A-Z]{3}")) return visible.toUpperCase(Locale.ROOT);
		LinkedHashSet<String> explicitCodes = new LinkedHashSet<>();
		Matcher explicitCode = EXPLICIT_IATA_IN_TEXT.matcher(visible);
		while (explicitCode.find()) explicitCodes.add(explicitCode.group(1));
		if (explicitCodes.size() == 1) return explicitCodes.iterator().next();
		String normalized = visible.toUpperCase(Locale.ROOT);
		for (Map.Entry<String, String> airport : AIRPORT_NAME_TO_IATA.entrySet()) {
			if (normalized.contains(airport.getKey())) return airport.getValue();
		}
		return null;
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
			return LocalDate.parse(normalized);
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

		return null;
	}

	private String normalizeDateText(String value) {
		return value == null ? "" : value.trim().replaceAll("\\s+", " ");
	}

	private LocalDate localDate(String year, String month, String day) {
		try {
			return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
		} catch (DateTimeException | NumberFormatException exception) {
			return null;
		}
	}

	private LocalDate localDateFromDateTime(String value) {
		if (value == null) return null;
		try {
			return OffsetDateTime.parse(value).toLocalDate();
		} catch (DateTimeParseException ignored) {
			// Most document times are local and intentionally have no offset.
		}
		try {
			return LocalDateTime.parse(value).toLocalDate();
		} catch (DateTimeParseException ignored) {
			return null;
		}
	}

	private record ParsedPass(
			BoardingPassExtractionResponse response,
			List<String> flightNumberCandidates,
			int visibleSegmentCount) {
	}

	private record CoverageAudit(
			boolean recoveryRequired,
			List<String> reasons,
			int normalizedUsableCount) {
	}

	private record IndexedSegment(int documentOrder, DocumentFlightSegment segment) {
	}

	private record FlightCandidate(String value, int documentOrder) {
	}

	private record RecoveryEvidence(
			List<String> flightNumberCandidates,
			int visibleSegmentCount,
			List<IndexedSegment> segments,
			List<FlightCandidate> orderedFlightCandidates) {
	}

	private record MergeOutcome(
			List<DocumentFlightSegment> segments,
			List<String> conflicts) {
	}
}
