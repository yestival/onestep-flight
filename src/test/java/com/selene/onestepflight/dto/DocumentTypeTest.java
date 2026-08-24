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
	void responseRecomputesTheCheckInSignalInsteadOfTrustingInput() {
		BoardingPassExtractionResponse itinerary = response(DocumentType.ITINERARY, 0.99d, true);
		BoardingPassExtractionResponse uncertainBoardingPass = response(DocumentType.BOARDING_PASS, 0.84d, true);
		BoardingPassExtractionResponse trustedBoardingPass = response(DocumentType.BOARDING_PASS, 0.85d, false);

		assertThat(itinerary.boardingPassDetected()).isFalse();
		assertThat(itinerary.requiresDocumentConfirmation()).isFalse();
		assertThat(uncertainBoardingPass.boardingPassDetected()).isFalse();
		assertThat(uncertainBoardingPass.requiresDocumentConfirmation()).isTrue();
		assertThat(trustedBoardingPass.boardingPassDetected()).isTrue();
		assertThat(trustedBoardingPass.requiresDocumentConfirmation()).isFalse();
	}

	@Test
	void unknownDocumentAlwaysRequiresConfirmation() {
		BoardingPassExtractionResponse response = response(DocumentType.UNKNOWN, 1.0d, true);

		assertThat(response.boardingPassDetected()).isFalse();
		assertThat(response.requiresDocumentConfirmation()).isTrue();
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

	private BoardingPassExtractionResponse response(DocumentType type, double confidence,
			boolean callerSuppliedBoardingPassSignal) {
		Map<String, ExtractedField> fields = Map.of("document_type",
				new ExtractedField(type.name(), confidence, 0, null, "test"));
		return new BoardingPassExtractionResponse(type, "BA117", LocalDate.of(2026, 8, 24),
				"2026-08-24T08:20", "07:35", "LHR", "JFK", null, null, null,
				callerSuppliedBoardingPassSignal, false, fields, List.of(), "TEST", Instant.EPOCH);
	}
}
