package com.selene.onestepflight.dto;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Privacy-safe document classifications returned by the extraction adapter.
 * Unknown provider labels deliberately collapse to {@link #UNKNOWN} rather
 * than being exposed as an unbounded string.
 */
public enum DocumentType {
	BOARDING_PASS,
	ITINERARY,
	E_TICKET,
	BOOKING_CONFIRMATION,
	UNKNOWN;

	/** A boarding pass must meet this threshold before it can imply check-in. */
	public static final double HIGH_CONFIDENCE_THRESHOLD = 0.85d;

	@JsonCreator
	public static DocumentType fromProviderValue(String value) {
		if (value == null || value.isBlank()) return UNKNOWN;
		String normalized = value.trim()
				.replace('-', '_')
				.replace(' ', '_')
				.toUpperCase(Locale.ROOT);
		return switch (normalized) {
			case "BOARDING_PASS", "MOBILE_BOARDING_PASS" -> BOARDING_PASS;
			case "ITINERARY", "FLIGHT_ITINERARY" -> ITINERARY;
			case "E_TICKET", "ETICKET", "ELECTRONIC_TICKET", "TICKET" -> E_TICKET;
			case "BOOKING_CONFIRMATION", "RESERVATION_CONFIRMATION", "BOOKING" -> BOOKING_CONFIRMATION;
			default -> UNKNOWN;
		};
	}

	@JsonValue
	public String jsonValue() {
		return name();
	}

	public boolean impliesCheckInCompleted(double classificationConfidence) {
		return this == BOARDING_PASS && classificationConfidence >= HIGH_CONFIDENCE_THRESHOLD;
	}

	public boolean requiresConfirmation(double classificationConfidence) {
		return this == UNKNOWN || classificationConfidence < HIGH_CONFIDENCE_THRESHOLD;
	}

	public static boolean isTrustedBoardingPassSignal(String documentType, boolean highConfidenceSignal) {
		return highConfidenceSignal && fromProviderValue(documentType) == BOARDING_PASS;
	}
}
