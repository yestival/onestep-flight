package com.selene.onestepflight.dto;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One flight leg extracted from a travel document. The contract intentionally
 * excludes passenger identity, booking references, ticket numbers and barcodes.
 */
public record DocumentFlightSegment(
		String flightNumber,
		LocalDate departureDate,
		String departureMonthDay,
		String departureAirport,
		String arrivalAirport,
		String scheduledDeparture,
		String scheduledArrival,
		String boardingTime,
		String terminal,
		String gate,
		String seat,
		double confidence,
		Integer page,
		BoundingBox boundingBox,
		Map<String, ExtractedField> fields,
		FlightNumberConfidence flightNumberConfidence,
		Boolean requiresFlightNumberConfirmation,
		Boolean flightNumberConfirmedByUser,
		DateSource dateSource) {

	public enum FlightNumberConfidence {
		HIGH,
		MEDIUM,
		LOW
	}

	public enum DateSource {
		EXPLICIT_FULL_DATE,
		CURRENT_YEAR_FROM_MONTH_DAY,
		MISSING,
		CONFLICT
	}

	private static final Set<String> ALLOWED_FIELDS = Set.of(
			"flight_number", "departure_date", "departure_airport", "arrival_airport",
			"scheduled_departure", "scheduled_arrival", "boarding_time", "terminal",
			"departure_terminal", "arrival_terminal", "gate", "seat");

	/**
	 * Source-compatible constructor for callers that predate month/day-only dates.
	 */
	public DocumentFlightSegment(String flightNumber, LocalDate departureDate,
			String departureAirport, String arrivalAirport, String scheduledDeparture,
			String scheduledArrival, String boardingTime, String terminal, String gate,
			String seat, double confidence, Integer page, BoundingBox boundingBox,
			Map<String, ExtractedField> fields) {
		this(flightNumber, departureDate, monthDay(departureDate), departureAirport,
				arrivalAirport, scheduledDeparture, scheduledArrival, boardingTime,
				terminal, gate, seat, confidence, page, boundingBox, fields,
				defaultFlightConfidence(flightNumber), false, false, defaultDateSource(departureDate));
	}

	/**
	 * Source-compatible constructor for callers using the month/day-aware contract.
	 */
	public DocumentFlightSegment(String flightNumber, LocalDate departureDate,
			String departureMonthDay, String departureAirport, String arrivalAirport,
			String scheduledDeparture, String scheduledArrival, String boardingTime,
			String terminal, String gate, String seat, double confidence, Integer page,
			BoundingBox boundingBox, Map<String, ExtractedField> fields) {
		this(flightNumber, departureDate, departureMonthDay, departureAirport,
				arrivalAirport, scheduledDeparture, scheduledArrival, boardingTime,
				terminal, gate, seat, confidence, page, boundingBox, fields,
				defaultFlightConfidence(flightNumber), false, false, defaultDateSource(departureDate));
	}

	public DocumentFlightSegment {
		departureMonthDay = normalizeMonthDay(departureMonthDay);
		if (departureDate != null) departureMonthDay = monthDay(departureDate);
		flightNumberConfidence = Objects.requireNonNullElse(flightNumberConfidence,
				flightNumber == null ? FlightNumberConfidence.LOW : FlightNumberConfidence.MEDIUM);
		requiresFlightNumberConfirmation = Boolean.TRUE.equals(requiresFlightNumberConfirmation);
		flightNumberConfirmedByUser = Boolean.TRUE.equals(flightNumberConfirmedByUser);
		if (flightNumber == null || flightNumberConfidence == FlightNumberConfidence.LOW) {
			flightNumber = null;
			flightNumberConfidence = FlightNumberConfidence.LOW;
			requiresFlightNumberConfirmation = false;
			flightNumberConfirmedByUser = false;
		} else if (flightNumberConfidence == FlightNumberConfidence.HIGH) {
			requiresFlightNumberConfirmation = false;
			flightNumberConfirmedByUser = false;
		} else {
			requiresFlightNumberConfirmation = !Boolean.TRUE.equals(flightNumberConfirmedByUser);
		}
		dateSource = Objects.requireNonNullElse(dateSource, defaultDateSource(departureDate));
		if (dateSource == DateSource.CONFLICT) {
			departureDate = null;
			departureMonthDay = null;
		} else if (departureDate == null) {
			dateSource = DateSource.MISSING;
		}
		confidence = Math.max(0.0d, Math.min(1.0d, confidence));
		Map<String, ExtractedField> safeFields = new LinkedHashMap<>();
		if (fields != null) {
			fields.forEach((name, field) -> {
				if (ALLOWED_FIELDS.contains(name) && field != null) safeFields.put(name, field);
			});
		}
		fields = Map.copyOf(safeFields);
	}

	private static FlightNumberConfidence defaultFlightConfidence(String flightNumber) {
		return flightNumber == null || flightNumber.isBlank()
				? FlightNumberConfidence.LOW : FlightNumberConfidence.HIGH;
	}

	private static DateSource defaultDateSource(LocalDate departureDate) {
		return departureDate == null ? DateSource.MISSING : DateSource.EXPLICIT_FULL_DATE;
	}

	private static String monthDay(LocalDate date) {
		return date == null ? null : "%02d-%02d".formatted(date.getMonthValue(), date.getDayOfMonth());
	}

	private static String normalizeMonthDay(String value) {
		if (value == null || !value.matches("\\d{2}-\\d{2}")) return null;
		try {
			int month = Integer.parseInt(value.substring(0, 2));
			int day = Integer.parseInt(value.substring(3, 5));
			LocalDate.of(2000, month, day);
			return value;
		} catch (RuntimeException exception) {
			return null;
		}
	}
}
