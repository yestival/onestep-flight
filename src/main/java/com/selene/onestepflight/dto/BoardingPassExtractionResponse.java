package com.selene.onestepflight.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record BoardingPassExtractionResponse(
		DocumentType documentType,
		String flightNumber,
		LocalDate departureDate,
		String scheduledDeparture,
		String boardingTime,
		String departureAirport,
		String arrivalAirport,
		String terminal,
		String gate,
		String seat,
		boolean boardingPassDetected,
		boolean requiresDocumentConfirmation,
		Map<String, ExtractedField> fields,
		List<String> needsReviewFields,
		String dataSource,
		Instant extractedAt) {
	private static final Set<String> ALLOWED_EVIDENCE_FIELDS = Set.of(
			"document_type", "flight_number", "departure_date", "scheduled_departure",
			"boarding_time", "departure_airport", "arrival_airport", "terminal", "gate", "seat");

	public BoardingPassExtractionResponse {
		documentType = documentType == null ? DocumentType.UNKNOWN : documentType;
		Map<String, ExtractedField> safeFields = new LinkedHashMap<>();
		if (fields != null) {
			fields.forEach((name, field) -> {
				if (ALLOWED_EVIDENCE_FIELDS.contains(name) && field != null) safeFields.put(name, field);
			});
		}
		fields = Map.copyOf(safeFields);
		needsReviewFields = needsReviewFields == null ? List.of() : List.copyOf(needsReviewFields);
		double confidence = fields.containsKey("document_type") && fields.get("document_type") != null
				? fields.get("document_type").confidence() : 0.0d;
		boardingPassDetected = documentType.impliesCheckInCompleted(confidence);
		requiresDocumentConfirmation = documentType.requiresConfirmation(confidence);
	}
}
