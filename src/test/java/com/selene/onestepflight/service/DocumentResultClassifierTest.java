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
	void monthDayWithoutYearIsPartialDateEvidenceButNotLookupReady() {
		DocumentFlightSegment segment = new DocumentFlightSegment("BA117", null, "08-04",
				null, null, null, null, null, null, null, null,
				0.95d, null, null, Map.of());
		BoardingPassExtractionResponse response = response(DocumentType.E_TICKET, List.of(segment));

		assertThat(DocumentResultClassifier.classify(response)).isEqualTo(DocumentResultClassification.PARTIAL);
		assertThat(DocumentResultClassifier.hasAnyDepartureDate(response)).isTrue();
		assertThat(segment.departureDate()).isNull();
		assertThat(segment.departureMonthDay()).isEqualTo("08-04");
	}

	@Test
	void flightNumberAndDepartureDateAloneAreEnoughForLookup() {
		DocumentFlightSegment lookupReady = segment("BA117", LocalDate.of(2026, 8, 25));

		assertThat(lookupReady.departureAirport()).isNull();
		assertThat(lookupReady.arrivalAirport()).isNull();
		assertThat(lookupReady.terminal()).isNull();
		assertThat(lookupReady.gate()).isNull();
		assertThat(DocumentResultClassifier.classify(response(DocumentType.UNKNOWN,
				List.of(lookupReady))))
				.isEqualTo(DocumentResultClassification.USABLE);
	}

	@Test
	void incompleteLegCannotMakeAnotherIncompleteLegUsable() {
		assertThat(DocumentResultClassifier.classify(response(DocumentType.ITINERARY,
				List.of(segment("BA117", null), segment(null, LocalDate.of(2026, 8, 25))))))
				.isEqualTo(DocumentResultClassification.PARTIAL);
	}

	@Test
	void completeAndPartialLegsKeepTheWholeDocumentPartialWithoutDroppingOrReordering() {
		DocumentFlightSegment partialFirst = segment("BA101", null);
		DocumentFlightSegment completeSecond = segment("BA102", LocalDate.of(2026, 8, 25));
		DocumentFlightSegment dateOnlyThird = segment(null, LocalDate.of(2026, 8, 26));
		BoardingPassExtractionResponse response = response(DocumentType.ITINERARY,
				List.of(partialFirst, completeSecond, dateOnlyThird));

		assertThat(DocumentResultClassifier.classify(response)).isEqualTo(DocumentResultClassification.PARTIAL);
		assertThat(response.segments()).containsExactly(partialFirst, completeSecond, dateOnlyThird);
	}

	@Test
	void mediumConfidenceFlightRequiresExplicitUserConfirmationBeforeLookup() {
		DocumentFlightSegment pending = trustedSegment(
				DocumentFlightSegment.FlightNumberConfidence.MEDIUM, true, false);
		DocumentFlightSegment confirmed = trustedSegment(
				DocumentFlightSegment.FlightNumberConfidence.MEDIUM, false, true);

		assertThat(DocumentResultClassifier.classify(response(DocumentType.ITINERARY, List.of(pending))))
				.isEqualTo(DocumentResultClassification.PARTIAL);
		assertThat(DocumentResultClassifier.classify(response(DocumentType.ITINERARY, List.of(confirmed))))
				.isEqualTo(DocumentResultClassification.USABLE);
	}

	@Test
	void unresolvedCriticalConflictKeepsOtherwiseCompleteSegmentPartial() {
		DocumentFlightSegment complete = segment("BA117", LocalDate.of(2026, 8, 25));
		BoardingPassExtractionResponse response = new BoardingPassExtractionResponse(
				DocumentType.ITINERARY, null, null, null, null, null, null, null,
				null, null, null, false, false, Map.of(), List.of(complete),
				List.of("conflict.flight_number"), "TEST", Instant.EPOCH);

		assertThat(DocumentResultClassifier.classify(response)).isEqualTo(DocumentResultClassification.PARTIAL);
	}

	private BoardingPassExtractionResponse response(DocumentType type, List<DocumentFlightSegment> segments) {
		return new BoardingPassExtractionResponse(type, null, null, null, null, null, null, null,
				null, null, null, false, false, Map.of(), segments, List.of(), "TEST", Instant.EPOCH);
	}

	private DocumentFlightSegment segment(String flightNumber, LocalDate departureDate) {
		return new DocumentFlightSegment(flightNumber, departureDate, null, null, null, null, null,
				null, null, null, 0.95d, null, null, Map.of());
	}

	private DocumentFlightSegment trustedSegment(
			DocumentFlightSegment.FlightNumberConfidence confidence,
			boolean requiresConfirmation, boolean confirmedByUser) {
		return new DocumentFlightSegment("BA117", LocalDate.of(2026, 8, 25), "08-25",
				null, null, null, null, null, null, null, null, 0.95d, null, null,
				Map.of(), confidence, requiresConfirmation, confirmedByUser,
				DocumentFlightSegment.DateSource.EXPLICIT_FULL_DATE);
	}
}
