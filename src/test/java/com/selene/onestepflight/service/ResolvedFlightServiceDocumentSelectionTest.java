package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;
import com.selene.onestepflight.dto.DocumentFlightSegment;
import com.selene.onestepflight.dto.DocumentType;
import com.selene.onestepflight.dto.ExtractedField;
import com.selene.onestepflight.dto.ResolveFlightRequest;

class ResolvedFlightServiceDocumentSelectionTest {

	@Test
	void refusesToResolveAMultiSegmentDocumentUntilTravelerSelectsOne() {
		DocumentFlightSegment outbound = segment("BA117", LocalDate.of(2026, 8, 24), "LHR", "JFK");
		DocumentFlightSegment inbound = segment("BA178", LocalDate.of(2026, 9, 2), "JFK", "LHR");
		Map<String, ExtractedField> evidence = Map.of("document_type",
				new ExtractedField("ITINERARY", 0.99d, 0, null, "test"));
		BoardingPassExtractionResponse document = new BoardingPassExtractionResponse(
				DocumentType.ITINERARY, null, null, null, null, null, null, null,
				null, null, null, false, false, evidence, List.of(outbound, inbound),
				List.of(), "TEST", Instant.EPOCH);
		ResolvedFlightService service = new ResolvedFlightService(null, null, null, null);

		assertThatThrownBy(() -> service.resolve(new ResolveFlightRequest(
				"BA117", LocalDate.of(2026, 8, 24), false, null, document, false)))
				.isInstanceOf(InvalidDocumentException.class)
				.hasMessage("DOCUMENT_SEGMENT_SELECTION_REQUIRED");
	}

	private DocumentFlightSegment segment(String flightNumber, LocalDate date, String departure, String arrival) {
		return new DocumentFlightSegment(flightNumber, date, departure, arrival,
				date + "T08:20", date + "T11:10", null, null, null, null,
				0.99d, 0, null, Map.of());
	}
}
