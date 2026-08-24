package com.selene.onestepflight.service;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import com.selene.onestepflight.dto.ExtractedField;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class NutrientBoardingPassDocumentProcessor implements BoardingPassDocumentProcessor {

	private static final Logger LOGGER = LoggerFactory.getLogger(NutrientBoardingPassDocumentProcessor.class);
	private static final String EXTRACT_ENDPOINT = "https://api.nutrient.io/extraction/extract";
	private static final List<String> FIELD_NAMES = List.of(
			"document_type", "flight_number", "departure_date", "scheduled_departure",
			"boarding_time", "departure_airport", "arrival_airport", "terminal", "gate", "seat");

	private final RestClient restClient;
	private final ObjectMapper objectMapper;

	public NutrientBoardingPassDocumentProcessor(ObjectMapper objectMapper) {
		HttpClient.Builder clientBuilder = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(8))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.version(HttpClient.Version.HTTP_1_1);
		ProxySelector proxySelector = ProxySelector.getDefault();
		if (proxySelector != null) clientBuilder.proxy(proxySelector);
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(clientBuilder.build());
		requestFactory.setReadTimeout(Duration.ofSeconds(120));
		// Nutrient's extraction endpoint currently resets chunked multipart uploads.
		// Buffering is bounded by the controller's 10 MB upload limit and gives the
		// JDK client an exact Content-Length without changing TLS verification.
		this.restClient = RestClient.builder()
				.requestFactory(new BufferingClientHttpRequestFactory(requestFactory))
				.build();
		this.objectMapper = objectMapper;
	}

	@Override
	public BoardingPassExtractionResponse extract(byte[] documentBytes, String filename, String contentType) {
		String apiKey = System.getenv("NUTRIENT_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			throw new ExternalServiceNotConfiguredException("NUTRIENT");
		}
		apiKey = apiKey.trim();

		JsonNode response = null;
		for (int attempt = 1; attempt <= 2; attempt++) {
			try {
				response = requestExtraction(documentBytes, filename, contentType, apiKey);
				break;
			} catch (RestClientResponseException exception) {
				LOGGER.warn("provider=NUTRIENT stage=extraction httpStatus={} rootCauseType={} category=HTTP_REJECTED message={}",
						exception.getStatusCode().value(), exception.getClass().getName(),
						ProviderFailureDiagnostics.safeMessage(exception));
				throw new ExternalServiceUnavailableException("NUTRIENT_EXTRACTION_FAILED");
			} catch (RestClientException exception) {
				ProviderFailureDiagnostics.Failure failure = ProviderFailureDiagnostics.classify("NUTRIENT", exception);
				if (attempt == 1 && "CONNECTION_RESET".equals(failure.category())) {
					LOGGER.info("provider=NUTRIENT stage=extraction httpStatus=none category=CONNECTION_RESET retry=true");
					continue;
				}
				LOGGER.warn("provider=NUTRIENT stage=extraction httpStatus=none rootCauseType={} category={} message={}",
						failure.rootCauseType(), failure.category(), failure.safeMessage());
				throw new ExternalServiceUnavailableException("NUTRIENT_EXTRACTION_FAILED");
			} catch (JacksonException exception) {
				LOGGER.warn("provider=NUTRIENT stage=request-build httpStatus=none rootCauseType={} category=SERIALIZATION_ERROR message={}",
						exception.getClass().getName(), ProviderFailureDiagnostics.safeMessage(exception));
				throw new ExternalServiceUnavailableException("NUTRIENT_EXTRACTION_FAILED");
			}
		}

		return toResponse(response);
	}

	private JsonNode requestExtraction(byte[] documentBytes, String filename, String contentType, String apiKey)
			throws JacksonException {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", filePart(documentBytes, filename, contentType));
		body.add("instructions", instructionsPart());
		return restClient.post()
				.uri(EXTRACT_ENDPOINT)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(body)
				.retrieve()
				.body(JsonNode.class);
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
		properties.put("flight_number", stringProperty("Operating or marketed flight designator, for example BA117. Never return a booking reference, PNR, passenger name, ticket number, or barcode data."));
		properties.put("departure_date", stringProperty("Local departure date in ISO YYYY-MM-DD format."));
		properties.put("scheduled_departure", stringProperty("Scheduled local departure date and time in ISO YYYY-MM-DDTHH:mm format when shown."));
		properties.put("boarding_time", stringProperty("Local boarding time in HH:mm format when shown."));
		properties.put("departure_airport", stringProperty("Three-letter IATA departure airport code."));
		properties.put("arrival_airport", stringProperty("Three-letter IATA arrival airport code."));
		properties.put("terminal", stringProperty("Departure terminal exactly as printed, without guessing."));
		properties.put("gate", stringProperty("Departure gate exactly as printed, without guessing."));
		properties.put("seat", stringProperty("Seat number exactly as printed, when present."));
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("description", "Extract only the listed flight fields. Do not guess missing values. Never extract or return passenger names, PNRs, booking references, ticket numbers, passport data, or barcode contents.");
		schema.put("properties", properties);
		// Non-flight and unclear files must still produce UNKNOWN instead of failing
		// validation or encouraging the model to invent a flight identity.
		schema.put("required", List.of("document_type"));
		return schema;
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
		if (response == null || response.path("output").path("data").isMissingNode()) {
			throw new ExternalServiceUnavailableException("NUTRIENT_RESPONSE_INVALID");
		}

		JsonNode data = response.path("output").path("data");
		JsonNode metadata = response.path("output").path("metadata");
		Map<String, ExtractedField> fields = new LinkedHashMap<>();
		for (String fieldName : FIELD_NAMES) {
			fields.put(fieldName, extractedField(data, metadata, fieldName));
		}
		ExtractedField rawDocumentType = fields.get("document_type");
		DocumentType documentType = DocumentType.fromProviderValue(rawDocumentType.value());
		fields.put("document_type", new ExtractedField(documentType.name(), rawDocumentType.confidence(),
				rawDocumentType.page(), rawDocumentType.boundingBox(), rawDocumentType.sourceLabel()));

		List<String> needsReview = new ArrayList<>();
		for (String fieldName : FIELD_NAMES) {
			ExtractedField field = fields.get(fieldName);
			double threshold = "document_type".equals(fieldName)
					? DocumentType.HIGH_CONFIDENCE_THRESHOLD
					: ("flight_number".equals(fieldName) || "departure_date".equals(fieldName)) ? 0.8 : 0.65;
			if (field.value() == null || field.confidence() < threshold) needsReview.add(fieldName);
		}

		double documentTypeConfidence = fields.get("document_type").confidence();
		if (documentType == DocumentType.UNKNOWN && !needsReview.contains("document_type")) {
			needsReview.add(0, "document_type");
		}
		boolean boardingPassDetected = documentType.impliesCheckInCompleted(documentTypeConfidence);
		return new BoardingPassExtractionResponse(
				documentType,
				normalizeFlightNumber(fields.get("flight_number").value()),
				parseDate(fields.get("departure_date").value()),
				fields.get("scheduled_departure").value(),
				fields.get("boarding_time").value(),
				upper(fields.get("departure_airport").value()),
				upper(fields.get("arrival_airport").value()),
				fields.get("terminal").value(),
				fields.get("gate").value(),
				fields.get("seat").value(),
				boardingPassDetected,
				documentType.requiresConfirmation(documentTypeConfidence),
				fields,
				List.copyOf(needsReview),
				"NUTRIENT_DWS_DATA_EXTRACTION",
				Instant.now());
	}

	private ExtractedField extractedField(JsonNode data, JsonNode metadata, String name) {
		String value = valueOrNull(data.path(name));
		JsonNode fieldMetadata = metadata.path(name);
		double confidence = fieldMetadata.path("confidence").asDouble(0.0);
		Integer page = fieldMetadata.path("pageIndex").isNumber() ? fieldMetadata.path("pageIndex").asInt() : null;
		BoundingBox box = boundingBox(fieldMetadata.path("bbox"));
		return new ExtractedField(value, confidence, page, box, "Nutrient citation: " + name);
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
		return value == null ? null : value.replaceAll("[\\s-]", "").toUpperCase();
	}

	private String upper(String value) {
		return value == null ? null : value.toUpperCase();
	}

	private LocalDate parseDate(String value) {
		try {
			return value == null ? null : LocalDate.parse(value);
		} catch (DateTimeParseException exception) {
			return null;
		}
	}
}
