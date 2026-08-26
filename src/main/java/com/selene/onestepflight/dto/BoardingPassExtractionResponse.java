package com.selene.onestepflight.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record BoardingPassExtractionResponse(
		DocumentType documentType,
		String flightNumber,
		LocalDate departureDate,
		String departureMonthDay,
		DocumentFlightSegment.FlightNumberConfidence flightNumberConfidence,
		Boolean requiresFlightNumberConfirmation,
		Boolean flightNumberConfirmedByUser,
		DocumentFlightSegment.DateSource dateSource,
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
			"terminal", "departure_terminal", "arrival_terminal", "gate", "seat");

	/**
	 * Source-compatible constructor for callers that predate month/day-only dates.
	 */
	public BoardingPassExtractionResponse(DocumentType documentType, String flightNumber,
			LocalDate departureDate, String scheduledDeparture, String scheduledArrival,
			String boardingTime, String departureAirport, String arrivalAirport,
			String terminal, String gate, String seat, boolean boardingPassDetected,
			boolean requiresDocumentConfirmation, Map<String, ExtractedField> fields,
			List<DocumentFlightSegment> segments, List<String> needsReviewFields,
			String dataSource, Instant extractedAt) {
		this(documentType, flightNumber, departureDate, monthDay(departureDate),
				defaultFlightConfidence(flightNumber), false, false, defaultDateSource(departureDate),
				scheduledDeparture, scheduledArrival, boardingTime, departureAirport,
				arrivalAirport, terminal, gate, seat, boardingPassDetected,
				requiresDocumentConfirmation, fields, segments, needsReviewFields,
				dataSource, extractedAt);
	}

	/**
	 * Source-compatible constructor for callers using the month/day-aware contract.
	 */
	public BoardingPassExtractionResponse(DocumentType documentType, String flightNumber,
			LocalDate departureDate, String departureMonthDay, String scheduledDeparture,
			String scheduledArrival, String boardingTime, String departureAirport,
			String arrivalAirport, String terminal, String gate, String seat,
			boolean boardingPassDetected, boolean requiresDocumentConfirmation,
			Map<String, ExtractedField> fields, List<DocumentFlightSegment> segments,
			List<String> needsReviewFields, String dataSource, Instant extractedAt) {
		this(documentType, flightNumber, departureDate, departureMonthDay,
				defaultFlightConfidence(flightNumber), false, false, defaultDateSource(departureDate),
				scheduledDeparture, scheduledArrival, boardingTime, departureAirport,
				arrivalAirport, terminal, gate, seat, boardingPassDetected,
				requiresDocumentConfirmation, fields, segments, needsReviewFields,
				dataSource, extractedAt);
	}

	public BoardingPassExtractionResponse {
		documentType = documentType == null ? DocumentType.UNKNOWN : documentType;
		departureMonthDay = normalizeMonthDay(departureMonthDay);
		if (departureDate != null) departureMonthDay = monthDay(departureDate);
		flightNumberConfidence = Objects.requireNonNullElse(flightNumberConfidence,
				defaultFlightConfidence(flightNumber));
		requiresFlightNumberConfirmation = Boolean.TRUE.equals(requiresFlightNumberConfirmation);
		flightNumberConfirmedByUser = Boolean.TRUE.equals(flightNumberConfirmedByUser);
		dateSource = Objects.requireNonNullElse(dateSource, defaultDateSource(departureDate));
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
			departureMonthDay = segment.departureMonthDay();
			flightNumberConfidence = segment.flightNumberConfidence();
			requiresFlightNumberConfirmation = segment.requiresFlightNumberConfirmation();
			flightNumberConfirmedByUser = segment.flightNumberConfirmedByUser();
			dateSource = segment.dateSource();
			scheduledDeparture = segment.scheduledDeparture();
			scheduledArrival = segment.scheduledArrival();
			boardingTime = segment.boardingTime();
			departureAirport = segment.departureAirport();
			arrivalAirport = segment.arrivalAirport();
			terminal = segment.terminal();
			gate = segment.gate();
			seat = segment.seat();
		} else if (segments.size() > 1) {
			// Multi-leg documents keep every segment. The resolution service selects
			// its lookup leg from the explicit flight-number/date request, never from
			// an arbitrary top-level value or by discarding the remaining itinerary.
			flightNumber = null;
			departureDate = null;
			departureMonthDay = null;
			flightNumberConfidence = DocumentFlightSegment.FlightNumberConfidence.LOW;
			requiresFlightNumberConfirmation = false;
			flightNumberConfirmedByUser = false;
			dateSource = DocumentFlightSegment.DateSource.MISSING;
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
		// Document classification and provider confidence remain internal evidence,
		// not journey-state authority. In particular, they must never
		// silently mark check-in complete or inject an extra business branch.
		boardingPassDetected = false;
		requiresDocumentConfirmation = false;
	}

	private static DocumentFlightSegment.FlightNumberConfidence defaultFlightConfidence(String flightNumber) {
		return flightNumber == null || flightNumber.isBlank()
				? DocumentFlightSegment.FlightNumberConfidence.LOW
				: DocumentFlightSegment.FlightNumberConfidence.HIGH;
	}

	private static DocumentFlightSegment.DateSource defaultDateSource(LocalDate departureDate) {
		return departureDate == null ? DocumentFlightSegment.DateSource.MISSING
				: DocumentFlightSegment.DateSource.EXPLICIT_FULL_DATE;
	}

	private static String monthDay(LocalDate date) {
		return date == null ? null : "%02d-%02d".formatted(date.getMonthValue(), date.getDayOfMonth());
	}

	private static String normalizeMonthDay(String value) {
		if (value == null || !value.matches("\\d{2}-\\d{2}")) return null;
		try {
			LocalDate.of(2000, Integer.parseInt(value.substring(0, 2)),
					Integer.parseInt(value.substring(3, 5)));
			return value;
		} catch (RuntimeException exception) {
			return null;
		}
	}
}
