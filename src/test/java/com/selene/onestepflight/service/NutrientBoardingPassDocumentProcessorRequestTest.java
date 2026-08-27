package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class NutrientBoardingPassDocumentProcessorRequestTest {

	private static final String ENDPOINT = "https://api.nutrient.io/extraction/extract";
	private static final String EMPTY_RESPONSE = """
			{"output":{"data":{"document_type":"UNKNOWN","segments":[]},
			"metadata":{"document_type":{"confidence":0.99}}}}
			""";
	private static final String FROZEN_PDF_INSTRUCTIONS = """
			{
			  "mode": "understand",
			  "schema": {
			    "type": "object",
			    "description": "Extract every distinct flight leg into segments. Do not silently choose the first leg and do not guess missing values. Never extract or return passenger names, PNRs, booking references, ticket numbers, passport data, or barcode contents.",
			    "properties": {
			      "document_type": {
			        "type": "string",
			        "enum": ["BOARDING_PASS", "ITINERARY", "E_TICKET", "BOOKING_CONFIRMATION", "UNKNOWN"],
			        "description": "Classify the visible document only. Use UNKNOWN for a non-flight document or when the type is unclear."
			      },
			      "segments": {
			        "type": "array",
			        "description": "All flight legs visible in document order. Return one item for a boarding pass or single-leg itinerary.",
			        "items": {
			          "type": "object",
			          "properties": {
			            "flight_number": {"type":"string","description":"Operating or marketed flight designator, for example BA117. Never return a booking reference, PNR, passenger name, ticket number, or barcode data."},
			            "departure_date": {"type":"string","description":"Local departure date in ISO YYYY-MM-DD format."},
			            "departure_airport": {"type":"string","description":"Three-letter IATA departure airport code."},
			            "arrival_airport": {"type":"string","description":"Three-letter IATA arrival airport code."},
			            "scheduled_departure": {"type":"string","description":"Scheduled local departure date and time in ISO YYYY-MM-DDTHH:mm format when shown."},
			            "scheduled_arrival": {"type":"string","description":"Scheduled local arrival date and time in ISO YYYY-MM-DDTHH:mm format when shown."},
			            "boarding_time": {"type":"string","description":"Local boarding time in HH:mm format when shown."},
			            "terminal": {"type":"string","description":"Departure terminal exactly as printed, without guessing."},
			            "gate": {"type":"string","description":"Departure gate exactly as printed, without guessing."},
			            "seat": {"type":"string","description":"Seat number exactly as printed, when present."}
			          }
			        }
			      }
			    },
			    "required": ["document_type", "segments"]
			  },
			  "citationsEnabled": true
			}
			""";

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void pdfUsesOneUnderstandRequestWithTheFrozenFinalV0InstructionsAndUnchangedFilePart()
			throws Exception {
		byte[] originalBytes = new byte[] { 0x25, 0x50, 0x44, 0x46, 0x00, (byte) 0xff, 0x2a };
		Fixture fixture = fixture();
		fixture.server().expect(request -> {
			assertCommonRequest(request, originalBytes, "final-v0.pdf", MediaType.APPLICATION_PDF_VALUE);
			String instructions = multipartPartBody(request, "instructions");
			assertThat(mapper.readTree(instructions))
					.as("the complete PDF instructions JSON is a frozen pre-image-change baseline")
					.isEqualTo(mapper.readTree(FROZEN_PDF_INSTRUCTIONS));
		}).andRespond(withSuccess(EMPTY_RESPONSE, MediaType.APPLICATION_JSON));

		var response = fixture.processor().extract(originalBytes, "final-v0.pdf",
				MediaType.APPLICATION_PDF_VALUE);

		assertThat(response.dataSource()).isEqualTo("NUTRIENT_DWS_DATA_EXTRACTION");
		fixture.server().verify();
	}

	@ParameterizedTest
	@CsvSource({
			"image/jpeg, boarding-pass.jpg",
			"image/png, boarding-pass.png"
	})
	void imageUsesOneUnderstandRequestWithTheImageSchemaAndNoOtherRecognitionMode(
			String contentType, String filename) throws Exception {
		byte[] originalBytes = new byte[] { (byte) 0x89, 0x50, 0x4e, 0x47, 0x00, (byte) 0xfe, 0x55 };
		Fixture fixture = fixture();
		fixture.server().expect(request -> {
			assertCommonRequest(request, originalBytes, filename, contentType);
			String instructions = multipartPartBody(request, "instructions");
			JsonNode json = mapper.readTree(instructions);
			assertThat(json.path("mode").asText()).isEqualTo("understand");
			assertThat(json.path("schema"))
					.isEqualTo(mapper.valueToTree(fixture.processor().imageDocumentSchema()));
			assertThat(json.has("structure")).isFalse();
			assertThat(json.has("agentic")).isFalse();
			assertThat(instructions).doesNotContain("processor/ocr", "chi_sim", "english", "spanish",
					"languageOptions", "language_options");
		}).andRespond(withSuccess(EMPTY_RESPONSE, MediaType.APPLICATION_JSON));

		var response = fixture.processor().extract(originalBytes, filename, contentType);

		assertThat(response.dataSource()).isEqualTo("NUTRIENT_DWS_IMAGE_UNDERSTAND");
		fixture.server().verify();
	}

	private void assertCommonRequest(org.springframework.http.HttpRequest request, byte[] originalBytes,
			String filename, String contentType) {
		assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
		assertThat(request.getURI().toString()).isEqualTo(ENDPOINT);
		assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key");
		assertThat(request.getHeaders().getContentType()).isNotNull();
		assertThat(request.getHeaders().getContentType().isCompatibleWith(MediaType.MULTIPART_FORM_DATA))
				.isTrue();
		byte[] body = ((MockClientHttpRequest) request).getBodyAsBytes();
		assertThat(multipartPartBytes(request, "file")).containsExactly(originalBytes);
		String multipart = new String(body, StandardCharsets.ISO_8859_1);
		assertThat(multipart).contains("name=\"file\"", "filename=\"" + filename + "\"",
				"Content-Type: " + contentType, "name=\"instructions\"",
				"Content-Type: application/json");
	}

	private String multipartPartBody(org.springframework.http.HttpRequest request, String partName) {
		return new String(multipartPartBytes(request, partName), StandardCharsets.ISO_8859_1);
	}

	private byte[] multipartPartBytes(org.springframework.http.HttpRequest request, String partName) {
		String boundary = request.getHeaders().getContentType().getParameter("boundary");
		String multipart = new String(((MockClientHttpRequest) request).getBodyAsBytes(),
				StandardCharsets.ISO_8859_1);
		int part = multipart.indexOf("name=\"" + partName + "\"");
		assertThat(part).isGreaterThanOrEqualTo(0);
		int bodyStart = multipart.indexOf("\r\n\r\n", part);
		assertThat(bodyStart).isGreaterThan(part);
		int bodyEnd = multipart.indexOf("\r\n--" + boundary, bodyStart + 4);
		assertThat(bodyEnd).isGreaterThan(bodyStart);
		return multipart.substring(bodyStart + 4, bodyEnd).getBytes(StandardCharsets.ISO_8859_1);
	}

	private Fixture fixture() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		NutrientBoardingPassDocumentProcessor processor =
				new NutrientBoardingPassDocumentProcessor(mapper, builder.build(), "test-key");
		return new Fixture(processor, server);
	}

	private record Fixture(NutrientBoardingPassDocumentProcessor processor,
			MockRestServiceServer server) {
	}
}
