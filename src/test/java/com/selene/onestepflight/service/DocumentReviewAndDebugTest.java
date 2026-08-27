package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;
import com.selene.onestepflight.dto.BoundingBox;
import com.selene.onestepflight.dto.DocumentFieldCandidate;
import com.selene.onestepflight.dto.DocumentFlightSegment;
import com.selene.onestepflight.dto.DocumentReviewIssue;
import com.selene.onestepflight.dto.DocumentType;

import tools.jackson.databind.ObjectMapper;

class DocumentReviewAndDebugTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void onlyDistinctUnresolvedCriticalValuesBlock() throws Exception {
		DocumentFieldCandidate understand = candidate("flight_number", "BA 117", "BA117",
				DocumentFieldCandidate.Source.UNDERSTAND);
		DocumentFieldCandidate structureSame = candidate("flight_number", "BA117", "BA117",
				DocumentFieldCandidate.Source.STRUCTURE);
		DocumentFieldCandidate structureOther = candidate("flight_number", "BA118", "BA118",
				DocumentFieldCandidate.Source.STRUCTURE);

		DocumentReviewIssue corroborated = new DocumentReviewIssue(0, "flight_number",
				DocumentReviewIssue.Kind.ACTIONABLE, true,
				List.of(understand, structureSame), null, "MULTIPLE_UNRESOLVED_CANDIDATES");
		DocumentReviewIssue unresolved = new DocumentReviewIssue(0, "flight_number",
				DocumentReviewIssue.Kind.ACTIONABLE, false,
				List.of(understand, structureOther), null, "MULTIPLE_UNRESOLVED_CANDIDATES");
		DocumentReviewIssue auxiliary = new DocumentReviewIssue(0, "departure_time",
				DocumentReviewIssue.Kind.ACTIONABLE, true,
				List.of(candidate("departure_time", "09:20", "09:20",
						DocumentFieldCandidate.Source.UNDERSTAND),
						candidate("departure_time", "09:25", "09:25",
								DocumentFieldCandidate.Source.STRUCTURE)),
				null, "AUXILIARY_EVIDENCE_DISAGREEMENT");
		DocumentReviewIssue manualFallback = new DocumentReviewIssue(0, "departure_date",
				DocumentReviewIssue.Kind.ACTIONABLE, true, List.of(), null,
				"LEGACY_CONFLICT_REQUIRES_MANUAL_INPUT");
		DocumentReviewIssue loneCandidate = new DocumentReviewIssue(0, "departure_date",
				DocumentReviewIssue.Kind.ACTIONABLE, true,
				List.of(candidate("departure_date", "2026-08-04", "2026-08-04",
						DocumentFieldCandidate.Source.STRUCTURE)), null,
				"MULTIPLE_UNRESOLVED_CANDIDATES");

		assertThat(corroborated.blocking()).isFalse();
		assertThat(corroborated.kind()).isEqualTo(DocumentReviewIssue.Kind.ADVISORY);
		assertThat(unresolved.blocking()).isTrue();
		assertThat(unresolved.kind()).isEqualTo(DocumentReviewIssue.Kind.ACTIONABLE);
		assertThat(auxiliary.blocking()).isFalse();
		assertThat(auxiliary.kind()).isEqualTo(DocumentReviewIssue.Kind.ADVISORY);
		assertThat(manualFallback.blocking()).isTrue();
		assertThat(loneCandidate.blocking()).isFalse();
		assertThat(mapper.writeValueAsString(unresolved))
				.contains("\"value\":\"BA117\"")
				.doesNotContain("rawValue", "BA 117");
	}

	@Test
	void singleLowConfidenceValidFlightIsRetainedAndLookupReady() {
		DocumentFlightSegment segment = new DocumentFlightSegment("Q0999",
				LocalDate.of(2026, 8, 4), "08-04", null, null, null, null, null,
				null, null, null, 0.25d, null, null, Map.of(),
				DocumentFlightSegment.FlightNumberConfidence.LOW, true, false,
				DocumentFlightSegment.DateSource.EXPLICIT_FULL_DATE);
		BoardingPassExtractionResponse response = new BoardingPassExtractionResponse(
				DocumentType.ITINERARY, null, null, null, null, null, null, null,
				null, null, null, false, false, Map.of(), List.of(segment), List.of(),
				"TEST", Instant.EPOCH);

		assertThat(segment.flightNumber()).isEqualTo("Q0999");
		assertThat(segment.requiresFlightNumberConfirmation()).isFalse();
		assertThat(DocumentResultClassifier.classify(response))
				.isEqualTo(DocumentResultClassification.USABLE);
	}

	@Test
	void documentFlightSegmentNormalizesUnicodeHyphens() {
		assertThat(segmentWithFlightNumber("BA‑117").flightNumber()).isEqualTo("BA117");
		assertThat(segmentWithFlightNumber("QF–12").flightNumber()).isEqualTo("QF12");
		assertThat(segmentWithFlightNumber("QF—12").flightNumber()).isEqualTo("QF12");
	}

	private DocumentFieldCandidate candidate(String field, String raw, String normalized,
			DocumentFieldCandidate.Source source) {
		return new DocumentFieldCandidate(field, raw, normalized, source, 0.7d, 0,
				new BoundingBox(1, 2, 3, 4), DocumentFieldCandidate.AnchorType.FLIGHT_CARD,
				0, DocumentFieldCandidate.Disposition.CANDIDATE, "TEST_EVIDENCE");
	}

	private DocumentFlightSegment segmentWithFlightNumber(String flightNumber) {
		return new DocumentFlightSegment(flightNumber, LocalDate.of(2026, 8, 4),
				"SYD", "SIN", null, null, null, null, null, null,
				0.4d, null, null, Map.of());
	}
}
