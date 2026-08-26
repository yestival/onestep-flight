package com.selene.onestepflight.service;

import java.util.List;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;
import com.selene.onestepflight.dto.DocumentFlightSegment;

public final class DocumentResultClassifier {

	private DocumentResultClassifier() {
	}

	public static DocumentResultClassification classify(BoardingPassExtractionResponse result) {
		List<DocumentFlightSegment> segments = result == null ? List.of() : result.segments();
		if (segments == null || segments.isEmpty()) return DocumentResultClassification.UNUSABLE;
		boolean anyIdentityField = false;
		boolean allLookupReady = true;
		for (int index = 0; index < segments.size(); index++) {
			DocumentFlightSegment segment = segments.get(index);
			if (segment == null) continue;
			boolean hasFlightNumber = hasText(segment.flightNumber());
			boolean hasDepartureDate = segment.departureDate() != null;
			allLookupReady &= hasFlightNumber && hasDepartureDate
					&& trustedFlightNumber(segment) && !hasCriticalConflict(result, index);
			anyIdentityField |= hasFlightNumber || hasDepartureDate || hasText(segment.departureMonthDay());
		}
		if (allLookupReady) return DocumentResultClassification.USABLE;
		return anyIdentityField ? DocumentResultClassification.PARTIAL : DocumentResultClassification.UNUSABLE;
	}

	private static boolean trustedFlightNumber(DocumentFlightSegment segment) {
		return segment.flightNumberConfidence() == DocumentFlightSegment.FlightNumberConfidence.HIGH
				|| (segment.flightNumberConfirmedByUser()
						&& !segment.requiresFlightNumberConfirmation());
	}

	private static boolean hasCriticalConflict(BoardingPassExtractionResponse result, int index) {
		if (result == null || result.needsReviewFields() == null) return false;
		String prefix = result.segments().size() == 1 ? "" : "segments[" + index + "].";
		return result.needsReviewFields().stream().anyMatch(value ->
				(prefix + "conflict.flight_number").equals(value)
						|| (prefix + "conflict.departure_date").equals(value));
	}

	public static boolean hasAnyFlightNumber(BoardingPassExtractionResponse result) {
		return result != null && result.segments().stream()
				.anyMatch(segment -> segment != null && hasText(segment.flightNumber()));
	}

	public static boolean hasAnyDepartureDate(BoardingPassExtractionResponse result) {
		return result != null && result.segments().stream()
				.anyMatch(segment -> segment != null && (segment.departureDate() != null
						|| hasText(segment.departureMonthDay())));
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
