package com.selene.onestepflight.dto;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * One flight leg extracted from a travel document. The contract intentionally
 * excludes passenger identity, booking references, ticket numbers and barcodes.
 */
public record DocumentFlightSegment(
		String flightNumber,
		LocalDate departureDate,
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
		Map<String, ExtractedField> fields) {

	private static final Set<String> ALLOWED_FIELDS = Set.of(
			"flight_number", "departure_date", "departure_airport", "arrival_airport",
			"scheduled_departure", "scheduled_arrival", "boarding_time", "terminal", "gate", "seat");

	public DocumentFlightSegment {
		confidence = Math.max(0.0d, Math.min(1.0d, confidence));
		Map<String, ExtractedField> safeFields = new LinkedHashMap<>();
		if (fields != null) {
			fields.forEach((name, field) -> {
				if (ALLOWED_FIELDS.contains(name) && field != null) safeFields.put(name, field);
			});
		}
		fields = Map.copyOf(safeFields);
	}
}
