package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;
import com.selene.onestepflight.dto.DocumentFlightSegment;
import com.selene.onestepflight.dto.DocumentType;

class DocumentResultClassifierTest {

	@Test
	void unknownWithNoSegmentsIsUnusable() {
		assertThat(DocumentResultClassifier.classify(response(DocumentType.UNKNOWN, List.of())))
				.isEqualTo(DocumentResultClassification.UNUSABLE);
	}

	@Test
	void recognizedTypeWithNoSegmentsIsUnusable() {
		assertThat(DocumentResultClassifier.classify(response(DocumentType.ITINERARY, List.of())))
				.isEqualTo(DocumentResultClassification.UNUSABLE);
	}

	@Test
	void oneIdentityFieldIsPartial() {
		assertThat(DocumentResultClassifier.classify(response(DocumentType.E_TICKET,
				List.of(segment("BA117", null)))))
				.isEqualTo(DocumentResultClassification.PARTIAL);
		assertThat(DocumentResultClassifier.classify(response(DocumentType.E_TICKET,
				List.of(segment(null, LocalDate.of(2026, 8, 25))))))
				.isEqualTo(DocumentResultClassification.PARTIAL);
	}

	@Test
	void unknownWithCompleteIdentityIsUsable() {
		assertThat(DocumentResultClassifier.classify(response(DocumentType.UNKNOWN,
				List.of(segment("BA117", LocalDate.of(2026, 8, 25))))))
				.isEqualTo(DocumentResultClassification.USABLE);
	}

	@Test
	void incompleteLegCannotMakeAnotherIncompleteLegUsable() {
		assertThat(DocumentResultClassifier.classify(response(DocumentType.ITINERARY,
				List.of(segment("BA117", null), segment(null, LocalDate.of(2026, 8, 25))))))
				.isEqualTo(DocumentResultClassification.PARTIAL);
	}

	private BoardingPassExtractionResponse response(DocumentType type, List<DocumentFlightSegment> segments) {
		return new BoardingPassExtractionResponse(type, null, null, null, null, null, null, null,
				null, null, null, false, false, Map.of(), segments, List.of(), "TEST", Instant.EPOCH);
	}

	private DocumentFlightSegment segment(String flightNumber, LocalDate departureDate) {
		return new DocumentFlightSegment(flightNumber, departureDate, null, null, null, null, null,
				null, null, null, 0.95d, null, null, Map.of());
	}
}
