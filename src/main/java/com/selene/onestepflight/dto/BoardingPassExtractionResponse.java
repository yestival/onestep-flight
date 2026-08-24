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
		String scheduledArrival,
		String boardingTime,
		String departureAirport,
		String arrivalAirport,
		String terminal,
		String gate,
		String seat,
		boolean boardingPassDetected,
		boolean requiresDocumentConfirmation,
		Map<String, ExtractedField> fields,
		List<DocumentFlightSegment> segments,
		List<String> needsReviewFields,
		String dataSource,
		Instant extractedAt) {
	private static final Set<String> ALLOWED_EVIDENCE_FIELDS = Set.of(
			"document_type", "flight_number", "departure_date", "scheduled_departure",
			"scheduled_arrival", "boarding_time", "departure_airport", "arrival_airport",
			"terminal", "gate", "seat");

	public BoardingPassExtractionResponse {
		documentType = documentType == null ? DocumentType.UNKNOWN : documentType;
		Map<String, ExtractedField> safeFields = new LinkedHashMap<>();
		if (fields != null) {
			fields.forEach((name, field) -> {
				if (ALLOWED_EVIDENCE_FIELDS.contains(name) && field != null) safeFields.put(name, field);
			});
		}
		fields = Map.copyOf(safeFields);
		segments = segments == null ? List.of() : segments.stream().filter(java.util.Objects::nonNull).toList();
		if (segments.size() == 1) {
			DocumentFlightSegment segment = segments.get(0);
			// The sole selected segment is canonical. Do not allow stale or client-supplied
			// top-level values to disagree with the segment being resolved.
			flightNumber = segment.flightNumber();
			departureDate = segment.departureDate();
			scheduledDeparture = segment.scheduledDeparture();
			scheduledArrival = segment.scheduledArrival();
			boardingTime = segment.boardingTime();
			departureAirport = segment.departureAirport();
			arrivalAirport = segment.arrivalAirport();
			terminal = segment.terminal();
			gate = segment.gate();
			seat = segment.seat();
		} else if (segments.size() > 1) {
			// A multi-leg itinerary must be selected by the traveler. Never let a
			// caller accidentally continue with whichever leg appeared first.
			flightNumber = null;
			departureDate = null;
			scheduledDeparture = null;
			scheduledArrival = null;
			boardingTime = null;
			departureAirport = null;
			arrivalAirport = null;
			terminal = null;
			gate = null;
			seat = null;
		}
		needsReviewFields = needsReviewFields == null ? List.of() : List.copyOf(needsReviewFields);
		double confidence = fields.containsKey("document_type") && fields.get("document_type") != null
				? fields.get("document_type").confidence() : 0.0d;
		boardingPassDetected = segments.size() == 1 && hasUsableIdentity(segments.get(0))
				&& documentType.impliesCheckInCompleted(confidence);
		requiresDocumentConfirmation = segments.size() > 1 || documentType.requiresConfirmation(confidence);
	}

	private static boolean hasUsableIdentity(DocumentFlightSegment segment) {
		return segment.flightNumber() != null && segment.departureDate() != null
				&& segment.departureAirport() != null && segment.arrivalAirport() != null;
	}
}
