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
		for (DocumentFlightSegment segment : segments) {
			if (segment == null) continue;
			boolean hasFlightNumber = hasText(segment.flightNumber());
			boolean hasDepartureDate = segment.departureDate() != null;
			if (hasFlightNumber && hasDepartureDate) return DocumentResultClassification.USABLE;
			anyIdentityField |= hasFlightNumber || hasDepartureDate;
		}
		return anyIdentityField ? DocumentResultClassification.PARTIAL : DocumentResultClassification.UNUSABLE;
	}

	public static boolean hasAnyFlightNumber(BoardingPassExtractionResponse result) {
		return result != null && result.segments().stream()
				.anyMatch(segment -> segment != null && hasText(segment.flightNumber()));
	}

	public static boolean hasAnyDepartureDate(BoardingPassExtractionResponse result) {
		return result != null && result.segments().stream()
				.anyMatch(segment -> segment != null && segment.departureDate() != null);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
