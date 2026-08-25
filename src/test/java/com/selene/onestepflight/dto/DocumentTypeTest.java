package com.selene.onestepflight.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class DocumentTypeTest {

	@Test
	void normalizesOnlyTheFiveSupportedDocumentTypes() {
		assertThat(DocumentType.fromProviderValue("BOARDING_PASS")).isEqualTo(DocumentType.BOARDING_PASS);
		assertThat(DocumentType.fromProviderValue("flight itinerary")).isEqualTo(DocumentType.ITINERARY);
		assertThat(DocumentType.fromProviderValue("e-ticket")).isEqualTo(DocumentType.E_TICKET);
		assertThat(DocumentType.fromProviderValue("reservation confirmation"))
				.isEqualTo(DocumentType.BOOKING_CONFIRMATION);
		assertThat(DocumentType.fromProviderValue("invoice")).isEqualTo(DocumentType.UNKNOWN);
		assertThat(DocumentType.fromProviderValue(null)).isEqualTo(DocumentType.UNKNOWN);
	}

	@Test
	void jsonInputAlsoNormalizesLegacyAndUnknownProviderLabels() throws Exception {
		ObjectMapper mapper = new ObjectMapper();

		assertThat(mapper.readValue("\"TICKET\"", DocumentType.class)).isEqualTo(DocumentType.E_TICKET);
		assertThat(mapper.readValue("\"invoice\"", DocumentType.class)).isEqualTo(DocumentType.UNKNOWN);
		assertThat(mapper.writeValueAsString(DocumentType.BOOKING_CONFIRMATION))
				.isEqualTo("\"BOOKING_CONFIRMATION\"");
	}

	@Test
	void onlyHighConfidenceBoardingPassImpliesCompletedCheckInAtTheBoundary() {
		double threshold = DocumentType.HIGH_CONFIDENCE_THRESHOLD;

		assertThat(DocumentType.BOARDING_PASS.impliesCheckInCompleted(threshold)).isTrue();
		assertThat(DocumentType.BOARDING_PASS.impliesCheckInCompleted(threshold - 0.0001d)).isFalse();
		assertThat(DocumentType.ITINERARY.impliesCheckInCompleted(1.0d)).isFalse();
		assertThat(DocumentType.E_TICKET.impliesCheckInCompleted(1.0d)).isFalse();
		assertThat(DocumentType.BOOKING_CONFIRMATION.impliesCheckInCompleted(1.0d)).isFalse();
		assertThat(DocumentType.UNKNOWN.impliesCheckInCompleted(1.0d)).isFalse();
	}

	@Test
	void responseNeutralizesDocumentClassificationBusinessFlagsInsteadOfTrustingInput() {
		BoardingPassExtractionResponse itinerary = response(DocumentType.ITINERARY, 0.99d, true);
		BoardingPassExtractionResponse uncertainBoardingPass = response(DocumentType.BOARDING_PASS, 0.84d, true);
		BoardingPassExtractionResponse trustedBoardingPass = response(DocumentType.BOARDING_PASS, 0.85d, false);

		assertThat(itinerary.boardingPassDetected()).isFalse();
		assertThat(itinerary.requiresDocumentConfirmation()).isFalse();
		assertThat(uncertainBoardingPass.boardingPassDetected()).isFalse();
		assertThat(uncertainBoardingPass.requiresDocumentConfirmation()).isFalse();
		assertThat(trustedBoardingPass.boardingPassDetected()).isFalse();
		assertThat(trustedBoardingPass.requiresDocumentConfirmation()).isFalse();
	}

	@Test
	void unknownDocumentDoesNotInjectAConfirmationBusinessFlag() {
		BoardingPassExtractionResponse response = response(DocumentType.UNKNOWN, 1.0d, true);

		assertThat(response.boardingPassDetected()).isFalse();
		assertThat(response.requiresDocumentConfirmation()).isFalse();
		assertThat(DocumentType.isTrustedBoardingPassSignal(response.documentType().name(),
				response.boardingPassDetected())).isFalse();
	}

	@Test
	void jsonBoundaryRecomputesBoardingPassSignalAndDropsUnknownEvidenceFields() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		String json = """
				{
				  "documentType": "ITINERARY",
				  "boardingPassDetected": true,
				  "requiresDocumentConfirmation": false,
				  "fields": {
				    "document_type": {"value":"ITINERARY","confidence":0.99},
				    "pnr": {"value":"must-not-cross-the-boundary","confidence":0.99}
				  }
				}
				""";

		BoardingPassExtractionResponse response = mapper.readValue(json, BoardingPassExtractionResponse.class);

		assertThat(response.boardingPassDetected()).isFalse();
		assertThat(response.fields()).containsOnlyKeys("document_type");
	}

	@Test
	void boardingPassWithoutOneUsableFlightSegmentCannotSkipCheckIn() {
		Map<String, ExtractedField> fields = Map.of("document_type",
				new ExtractedField("BOARDING_PASS", 0.99d, 0, null, "test"));
		BoardingPassExtractionResponse response = new BoardingPassExtractionResponse(
				DocumentType.BOARDING_PASS, "BA117", LocalDate.of(2026, 8, 24),
				"2026-08-24T08:20", null, null, "LHR", "JFK", null, null, null,
				true, false, fields, List.of(), List.of(), "TEST", Instant.EPOCH);

		assertThat(response.boardingPassDetected()).isFalse();
	}

	@Test
	void selectedSegmentIsCanonicalEvenWhenTopLevelInputDisagrees() {
		Map<String, ExtractedField> fields = Map.of("document_type",
				new ExtractedField("ITINERARY", 0.99d, 0, null, "test"));
		DocumentFlightSegment selected = new DocumentFlightSegment("BA178", LocalDate.of(2026, 9, 2),
				"JFK", "LHR", "2026-09-02T08:00", "2026-09-02T20:00", null,
				null, null, null, 0.99d, 0, null, Map.of());
		BoardingPassExtractionResponse response = new BoardingPassExtractionResponse(
				DocumentType.ITINERARY, "BA117", LocalDate.of(2026, 8, 24), null, null,
				null, "LHR", "JFK", null, null, null, false, false, fields,
				List.of(selected), List.of(), "TEST", Instant.EPOCH);

		assertThat(response.flightNumber()).isEqualTo("BA178");
		assertThat(response.departureDate()).isEqualTo(LocalDate.of(2026, 9, 2));
		assertThat(response.departureAirport()).isEqualTo("JFK");
	}

	@Test
	void segmentEvidenceWhitelistAlsoDropsSensitiveIdentityAndBarcodeFields() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		Map<String, ExtractedField> segmentFields = Map.of(
				"flight_number", new ExtractedField("BA117", 0.99d, 0, null, "test"),
				"departure_date", new ExtractedField("8月24日", 0.99d, 0, null, "test"),
				"pnr", new ExtractedField("SECRET-PNR", 0.99d, 0, null, "test"),
				"passenger_name", new ExtractedField("SECRET-NAME", 0.99d, 0, null, "test"),
				"ticket_number", new ExtractedField("SECRET-TICKET", 0.99d, 0, null, "test"),
				"passport", new ExtractedField("SECRET-PASSPORT", 0.99d, 0, null, "test"),
				"barcode", new ExtractedField("SECRET-BARCODE", 0.99d, 0, null, "test"));
		DocumentFlightSegment segment = new DocumentFlightSegment("BA117", LocalDate.of(2026, 8, 24),
				"LHR", "JFK", null, null, null, null, null, null,
				0.99d, 0, null, segmentFields);
		BoardingPassExtractionResponse response = new BoardingPassExtractionResponse(
				DocumentType.BOARDING_PASS, null, null, null, null, null, null, null,
				null, null, null, true, true, Map.of(), List.of(segment), List.of(), "TEST", Instant.EPOCH);

		assertThat(response.segments().get(0).fields()).containsOnlyKeys("flight_number", "departure_date");
		assertThat(mapper.writeValueAsString(response)).doesNotContain(
				"SECRET-PNR", "SECRET-NAME", "SECRET-TICKET", "SECRET-PASSPORT", "SECRET-BARCODE");
		assertThat(response.boardingPassDetected()).isFalse();
		assertThat(response.requiresDocumentConfirmation()).isFalse();
	}

	private BoardingPassExtractionResponse response(DocumentType type, double confidence,
			boolean callerSuppliedBoardingPassSignal) {
		Map<String, ExtractedField> fields = Map.of("document_type",
				new ExtractedField(type.name(), confidence, 0, null, "test"));
		DocumentFlightSegment segment = new DocumentFlightSegment("BA117", LocalDate.of(2026, 8, 24),
				"LHR", "JFK", "2026-08-24T08:20", "2026-08-24T11:10", "07:35",
				null, null, null, 0.99d, 0, null, Map.of());
		return new BoardingPassExtractionResponse(type, "BA117", LocalDate.of(2026, 8, 24),
				"2026-08-24T08:20", "2026-08-24T11:10", "07:35", "LHR", "JFK", null, null, null,
				callerSuppliedBoardingPassSignal, false, fields, List.of(segment), List.of(), "TEST", Instant.EPOCH);
	}
}
