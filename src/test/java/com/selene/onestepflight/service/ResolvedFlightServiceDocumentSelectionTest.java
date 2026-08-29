package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;
import com.selene.onestepflight.dto.DocumentFieldCandidate;
import com.selene.onestepflight.dto.DocumentFlightSegment;
import com.selene.onestepflight.dto.DocumentReviewIssue;
import com.selene.onestepflight.dto.DocumentType;
import com.selene.onestepflight.dto.ExtractedField;
import com.selene.onestepflight.dto.FlightResponse;
import com.selene.onestepflight.dto.ProviderFlightResult;
import com.selene.onestepflight.dto.ResolveFlightRequest;
import com.selene.onestepflight.dto.ResolvedFlightContext;

class ResolvedFlightServiceDocumentSelectionTest {

	private static final LocalDate DATE = LocalDate.of(2026, 8, 24);

	@Test
	void selectsTheMatchingLegWithoutRejectingTheOriginalMultiSegmentDocument() {
		CapturingMultiSource flights = new CapturingMultiSource(false);
		ResolvedFlightService service = service(flights);
		List<DocumentFlightSegment> originalSegments = List.of(
				segment("AF1668", LocalDate.of(2026, 8, 25), "CDG", "MAN"),
				segment("MF825", DATE, "XMN", "CDG"));
		BoardingPassExtractionResponse document = document(originalSegments);

		ResolvedFlightContext result = service.resolve(new ResolveFlightRequest(
				"mf-825", DATE, false, null, document, false));

		assertThat(result.flightNumber()).isEqualTo("MF825");
		assertThat(flights.departureHint).isEqualTo("XMN");
		assertThat(flights.arrivalHint).isEqualTo("CDG");
		assertThat(result.boardingPassDetected()).isFalse();
		assertThat(document.segments()).containsExactlyElementsOf(originalSegments);
	}

	@Test
	void explicitSegmentIndexSelectsThatExactLegAndPreservesTheOriginalOrder() {
		CapturingMultiSource flights = new CapturingMultiSource(false);
		ResolvedFlightService service = service(flights);
		DocumentFlightSegment first = segment("MF825", DATE, "XMN", "CDG");
		DocumentFlightSegment second = segment("MF825", DATE, "CDG", "MAN");
		List<DocumentFlightSegment> originalSegments = List.of(first, second);
		BoardingPassExtractionResponse document = document(originalSegments);

		service.resolve(new ResolveFlightRequest(
				"MF825", DATE, false, null, document, 1, false));

		assertThat(flights.departureHint).isEqualTo("CDG");
		assertThat(flights.arrivalHint).isEqualTo("MAN");
		assertThat(document.segments()).containsExactly(first, second);
	}

	@Test
	void mismatchedExplicitIndexIsRejectedBeforeAnyLiveLookup() {
		CapturingMultiSource flights = new CapturingMultiSource(false);
		ResolvedFlightService service = service(flights);
		DocumentFlightSegment requestedIdentity = segment("MF825", DATE, "XMN", "CDG");
		DocumentFlightSegment other = segment("AF1668", DATE.plusDays(1), "CDG", "MAN");
		BoardingPassExtractionResponse document = document(List.of(requestedIdentity, other));

		assertThatThrownBy(() -> service.resolve(new ResolveFlightRequest(
				"MF825", DATE, false, null, document, 1, false)))
				.isInstanceOf(InvalidDocumentException.class)
				.hasMessage("DOCUMENT_SEGMENT_SELECTION_INVALID");

		assertThat(flights.calls).isZero();
		assertThat(document.segments()).containsExactly(requestedIdentity, other);
	}

	@Test
	void explicitLookupFailureNeverBecomesADocumentFallbackSuccess() {
		CapturingMultiSource flights = new CapturingMultiSource(true);
		ResolvedFlightService service = service(flights);
		BoardingPassExtractionResponse document = document(List.of(
				segmentWithPrintedOperations("MF825", DATE, "XMN", "CDG"),
				segment("AF1668", LocalDate.of(2026, 8, 25), "CDG", "MAN")));

		assertThatThrownBy(() -> service.resolve(new ResolveFlightRequest(
				"MF825", DATE, false, null, document, false)))
				.isInstanceOf(AllFlightProvidersUnavailableException.class);
		assertThat(flights.calls).isEqualTo(1);
	}

	@Test
	void neverBuildsAFallbackByCombiningARequestWithADifferentDocumentIdentity() {
		ResolvedFlightService service = service(new CapturingMultiSource(true));
		BoardingPassExtractionResponse document = document(List.of(
				segment("AF1668", LocalDate.of(2026, 8, 25), "CDG", "MAN")));

		assertThatThrownBy(() -> service.resolve(new ResolveFlightRequest(
				"MF825", DATE, false, null, document, false)))
				.isInstanceOf(AllFlightProvidersUnavailableException.class);
	}

	@Test
	void liveSuccessNeverMergesFieldsFromTheSameFlightOnADifferentDate() {
		ResolvedFlightService service = service(new CapturingMultiSource(false));
		BoardingPassExtractionResponse document = document(List.of(
				segmentWithPrintedOperations("MF825", DATE.plusDays(1), "XMN", "CDG")));

		ResolvedFlightContext result = service.resolve(new ResolveFlightRequest(
				"MF825", DATE, false, null, document, false));

		assertThat(result.departureDate()).isEqualTo(DATE);
		assertThat(result.documentTerminal()).isNull();
		assertThat(result.documentGate()).isNull();
		assertThat(result.seat()).isNull();
	}

	@Test
	void resolvedEvidenceDropsUnknownKeysAndUntrustedSourceLabels() {
		ResolvedFlightService service = service(new CapturingMultiSource(false));
		Map<String, ExtractedField> safe = service.safeEvidence(Map.of(
				"passenger_name", new ExtractedField("SECRET NAME", 1.0d, 0, null, "private"),
				"departure_date", new ExtractedField("8月4日", 1.5d, 0, null, "SECRET PNR")));

		assertThat(safe).containsOnlyKeys("departure_date");
		assertThat(safe.get("departure_date").value()).isEqualTo("8月4日");
		assertThat(safe.get("departure_date").confidence()).isEqualTo(1.0d);
		assertThat(safe.get("departure_date").sourceLabel()).isNull();
	}

	@Test
	void unresolvedCriticalReviewStopsProviderLookupOnTheServer() {
		CapturingMultiSource flights = new CapturingMultiSource(false);
		ResolvedFlightService service = service(flights);
		DocumentReviewIssue blocking = new DocumentReviewIssue(0, "flight_number",
				DocumentReviewIssue.Kind.ACTIONABLE, true,
				List.of(reviewCandidate("MF825"), reviewCandidate("MF826")), null,
				"MULTIPLE_UNRESOLVED_CANDIDATES");
		BoardingPassExtractionResponse document = documentWithReview(
				List.of(segment("MF825", DATE, "XMN", "CDG")), List.of(), List.of(blocking));

		assertThatThrownBy(() -> service.resolve(new ResolveFlightRequest(
				"MF825", DATE, false, null, document, false)))
				.isInstanceOf(InvalidDocumentException.class)
				.hasMessage("DOCUMENT_REVIEW_REQUIRED");
		assertThat(flights.calls).isZero();
	}

	@Test
	void explicitCriticalReviewWithoutCandidatesStillStopsProviderLookup() {
		CapturingMultiSource flights = new CapturingMultiSource(false);
		ResolvedFlightService service = service(flights);
		DocumentReviewIssue blocking = new DocumentReviewIssue(0, "departure_date",
				DocumentReviewIssue.Kind.ACTIONABLE, true, List.of(), null,
				"LEGACY_CONFLICT_REQUIRES_MANUAL_INPUT");
		BoardingPassExtractionResponse document = documentWithReview(
				List.of(segment("MF825", DATE, "XMN", "CDG")), List.of(), List.of(blocking));

		assertThatThrownBy(() -> service.resolve(new ResolveFlightRequest(
				"MF825", DATE, false, null, document, false)))
				.isInstanceOf(InvalidDocumentException.class)
				.hasMessage("DOCUMENT_REVIEW_REQUIRED");
		assertThat(flights.calls).isZero();
	}

	@Test
	void legacyCriticalReviewStopsProviderLookupUnlessStructuredIssueReplacesIt() {
		CapturingMultiSource flights = new CapturingMultiSource(false);
		ResolvedFlightService service = service(flights);
		BoardingPassExtractionResponse legacy = documentWithReview(
				List.of(segment("MF825", DATE, "XMN", "CDG")),
				List.of("conflict.flight_number"), List.of());

		assertThatThrownBy(() -> service.resolve(new ResolveFlightRequest(
				"MF825", DATE, false, null, legacy, false)))
				.isInstanceOf(InvalidDocumentException.class)
				.hasMessage("DOCUMENT_REVIEW_REQUIRED");
		assertThat(flights.calls).isZero();
	}

	@Test
	void ambiguousUnselectedLegacyCriticalConflictStopsProviderLookup() {
		CapturingMultiSource flights = new CapturingMultiSource(false);
		ResolvedFlightService service = service(flights);
		BoardingPassExtractionResponse legacy = documentWithReview(List.of(
				segment("MF825", DATE, "XMN", "CDG"),
				segment("MF825", DATE, "CDG", "MAN")),
				List.of("segments[0].conflict.flight_number"), List.of());

		assertThatThrownBy(() -> service.resolve(new ResolveFlightRequest(
				"MF825", DATE, false, null, legacy, false)))
				.isInstanceOf(InvalidDocumentException.class)
				.hasMessage("DOCUMENT_REVIEW_REQUIRED");
		assertThat(flights.calls).isZero();
	}

	@Test
	void explicitSafeSegmentIgnoresLegacyConflictOnAnotherLeg() {
		CapturingMultiSource flights = new CapturingMultiSource(false);
		ResolvedFlightService service = service(flights);
		BoardingPassExtractionResponse legacy = documentWithReview(List.of(
				segment("MF825", DATE, "XMN", "CDG"),
				segment("AF1668", DATE.plusDays(1), "CDG", "MAN")),
				List.of("segments[1].conflict.flight_number"), List.of());

		service.resolve(new ResolveFlightRequest("MF825", DATE, false, null, legacy, 0, false));

		assertThat(flights.calls).isOne();
	}

	@Test
	void auxiliaryAdvisoryAndOtherSegmentsBlockingIssueDoNotBlockReadyLeg() {
		CapturingMultiSource flights = new CapturingMultiSource(false);
		ResolvedFlightService service = service(flights);
		DocumentReviewIssue auxiliary = new DocumentReviewIssue(0, "arrival_time",
				DocumentReviewIssue.Kind.ADVISORY, false, List.of(), "11:10",
				"AUXILIARY_EVIDENCE_DISAGREEMENT");
		DocumentReviewIssue otherLeg = new DocumentReviewIssue(1, "departure_date",
				DocumentReviewIssue.Kind.ACTIONABLE, true,
				List.of(reviewCandidate("departure_date", "2026-08-25", 1),
						reviewCandidate("departure_date", "2026-08-26", 1)),
				null, "MULTIPLE_UNRESOLVED_CANDIDATES");
		BoardingPassExtractionResponse document = documentWithReview(List.of(
				lowConfidenceSegment("MF825", DATE, "XMN", "CDG"),
				segment("AF1668", DATE.plusDays(1), "CDG", "MAN")),
				List.of(), List.of(auxiliary, otherLeg));

		service.resolve(new ResolveFlightRequest("MF825", DATE, false, null, document, false));

		assertThat(flights.calls).isOne();
	}

	@Test
	void unrelatedUnselectedConflictDoesNotBlockManualLookup() {
		CapturingMultiSource flights = new CapturingMultiSource(false);
		ResolvedFlightService service = service(flights);
		DocumentReviewIssue unrelated = new DocumentReviewIssue(0, "flight_number",
				DocumentReviewIssue.Kind.ACTIONABLE, true,
				List.of(reviewCandidate("AF1668"), reviewCandidate("AF1669")), null,
				"MULTIPLE_UNRESOLVED_CANDIDATES");
		BoardingPassExtractionResponse document = documentWithReview(List.of(
				segment("AF1668", DATE.plusDays(1), "CDG", "MAN")),
				List.of(), List.of(unrelated));

		service.resolve(new ResolveFlightRequest("MF825", DATE, false, null, document, false));

		assertThat(flights.calls).isOne();
	}

	private ResolvedFlightService service(MultiSourceFlightService flights) {
		return new ResolvedFlightService(new FlightService(null, null), flights, null, new FlightTimeService());
	}

	private BoardingPassExtractionResponse document(List<DocumentFlightSegment> segments) {
		return new BoardingPassExtractionResponse(DocumentType.ITINERARY, null, null,
				null, null, null, null, null, null, null, null,
				false, false, Map.of(), segments, List.of(), "TEST_DOCUMENT", Instant.EPOCH);
	}

	private BoardingPassExtractionResponse documentWithReview(List<DocumentFlightSegment> segments,
			List<String> reviewFields, List<DocumentReviewIssue> reviewIssues) {
		BoardingPassExtractionResponse base = document(segments);
		return new BoardingPassExtractionResponse(base.documentType(), base.flightNumber(),
				base.departureDate(), base.departureMonthDay(), base.flightNumberConfidence(),
				base.requiresFlightNumberConfirmation(), base.flightNumberConfirmedByUser(),
				base.dateSource(), base.scheduledDeparture(), base.scheduledArrival(),
				base.boardingTime(), base.departureAirport(), base.arrivalAirport(),
				base.terminal(), base.gate(), base.seat(), false, false, base.fields(),
				base.segments(), reviewFields, reviewIssues, base.dataSource(), base.extractedAt());
	}

	private DocumentFieldCandidate reviewCandidate(String value) {
		return reviewCandidate("flight_number", value, 0);
	}

	private DocumentFieldCandidate reviewCandidate(String field, String value, int segmentIndex) {
		return new DocumentFieldCandidate(field, value, value,
				DocumentFieldCandidate.Source.STRUCTURE, 0.6d, 0, null,
				DocumentFieldCandidate.AnchorType.FLIGHT_CARD, segmentIndex,
				DocumentFieldCandidate.Disposition.CANDIDATE, "TEST_CANDIDATE");
	}

	private DocumentFlightSegment segment(String flightNumber, LocalDate date, String departure, String arrival) {
		return new DocumentFlightSegment(flightNumber, date,
				"%02d-%02d".formatted(date.getMonthValue(), date.getDayOfMonth()), departure, arrival,
				date + "T08:20", date + "T11:10", null, null, null, null,
				0.99d, 0, null, Map.of());
	}

	private DocumentFlightSegment segmentWithPrintedOperations(String flightNumber, LocalDate date,
			String departure, String arrival) {
		return new DocumentFlightSegment(flightNumber, date, departure, arrival,
				date + "T08:20", date + "T11:10", "07:35", "T3", "B12", "22A",
				0.99d, 0, null, Map.of());
	}

	private DocumentFlightSegment lowConfidenceSegment(String flightNumber, LocalDate date,
			String departure, String arrival) {
		return new DocumentFlightSegment(flightNumber, date,
				"%02d-%02d".formatted(date.getMonthValue(), date.getDayOfMonth()), departure, arrival,
				date + "T08:20", date + "T11:10", null, null, null, null,
				0.35d, 0, null, Map.of(),
				DocumentFlightSegment.FlightNumberConfidence.LOW, false, false,
				DocumentFlightSegment.DateSource.EXPLICIT_FULL_DATE);
	}

	private static final class CapturingMultiSource extends MultiSourceFlightService {
		private final boolean fail;
		private String departureHint;
		private String arrivalHint;
		private int calls;

		private CapturingMultiSource(boolean fail) {
			super(List.of());
			this.fail = fail;
		}

		@Override
		public MultiSourceFlightSnapshot findFlight(String flightNumber, LocalDate departureDate,
				String departureAirportHint, String arrivalAirportHint, boolean refresh) {
			return findFlight(flightNumber, departureDate, departureAirportHint, arrivalAirportHint, null, refresh);
		}

		@Override
		public MultiSourceFlightSnapshot findFlight(String flightNumber, LocalDate departureDate,
				String departureAirportHint, String arrivalAirportHint,
				String scheduledDepartureHint, boolean refresh) {
			this.departureHint = departureAirportHint;
			this.arrivalHint = arrivalAirportHint;
			calls++;
			if (fail) {
				throw new AllFlightProvidersUnavailableException(Map.of("AERODATABOX",
						ProviderFlightResult.failure("AERODATABOX", "UPSTREAM_ERROR",
								"AERODATABOX_HTTP_400", Instant.EPOCH)));
			}
			OffsetDateTime scheduled = OffsetDateTime.parse("2026-08-24T08:20:00+08:00");
			FlightResponse flight = new FlightResponse(flightNumber, departureDate,
					"XMN", "Xiamen", "CDG", "Paris", scheduled, null, null,
					scheduled.plusHours(12), null, null, null, null, null, null, null,
					"EXPECTED", 0, 0, Instant.EPOCH, Instant.EPOCH, "TEST_LIVE",
					null, null, true, true, false);
			return new MultiSourceFlightSnapshot(flight, null, null, Map.of(), Map.of(), List.of(),
					Instant.EPOCH, Instant.EPOCH);
		}
	}
}
