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
import com.selene.onestepflight.dto.ExtractedField;

class ResolvedFlightServiceRouteHintTest {

	private final ResolvedFlightService service = new ResolvedFlightService(null, null, null, null);

	@Test
	void doesNotUseLowConfidenceAirportsAwaitingOrDeferredForReviewAsProviderFilters() {
		BoardingPassExtractionResponse document = document(0.54d,
				List.of("departure_airport", "segments[0].arrival_airport"));

		assertThat(service.documentRouteHint(document, "departure_airport")).isNull();
		assertThat(service.documentRouteHint(document, "arrival_airport")).isNull();
	}

	@Test
	void usesOnlyValidHighConfidenceDocumentAirportsWithoutReviewMarkers() {
		BoardingPassExtractionResponse document = document(0.95d, List.of());

		assertThat(service.documentRouteHint(document, "departure_airport")).isEqualTo("LHR");
		assertThat(service.documentRouteHint(document, "arrival_airport")).isEqualTo("JFK");
	}

	@Test
	void keepsCompatibleDocumentPayloadsWhenReviewMetadataIsAbsent() {
		BoardingPassExtractionResponse document = response(segment(Map.of()), Map.of(), List.of());

		assertThat(service.documentRouteHint(document, "departure_airport")).isEqualTo("LHR");
		assertThat(service.documentRouteHint(document, "arrival_airport")).isEqualTo("JFK");
	}

	@Test
	void rejectsAnAirportHintWhenItsEvidenceDoesNotMatchTheSelectedSegment() {
		BoardingPassExtractionResponse document = document(0.95d, List.of());
		Map<String, ExtractedField> mismatchedEvidence = Map.of(
				"departure_airport", field("CDG", 0.95d),
				"arrival_airport", field("JFK", 0.95d));
		DocumentFlightSegment segment = segment(mismatchedEvidence);
		document = response(segment, mismatchedEvidence, List.of());

		assertThat(service.documentRouteHint(document, "departure_airport")).isNull();
	}

	private BoardingPassExtractionResponse document(double airportConfidence, List<String> needsReview) {
		Map<String, ExtractedField> fields = Map.of(
				"departure_airport", field("LHR", airportConfidence),
				"arrival_airport", field("JFK", airportConfidence));
		return response(segment(fields), fields, needsReview);
	}

	private BoardingPassExtractionResponse response(DocumentFlightSegment segment,
			Map<String, ExtractedField> fields, List<String> needsReview) {
		return new BoardingPassExtractionResponse(DocumentType.ITINERARY, null, null,
				null, null, null, null, null, null, null, null,
				false, false, fields, List.of(segment), needsReview, "TEST", Instant.EPOCH);
	}

	private DocumentFlightSegment segment(Map<String, ExtractedField> fields) {
		return new DocumentFlightSegment("BA117", LocalDate.of(2026, 8, 25), "LHR", "JFK",
				null, null, null, null, null, null, 0.95d, 0, null, fields);
	}

	private ExtractedField field(String value, double confidence) {
		return new ExtractedField(value, confidence, 0, null, "test");
	}
}
