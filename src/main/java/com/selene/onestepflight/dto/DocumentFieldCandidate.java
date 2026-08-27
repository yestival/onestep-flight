package com.selene.onestepflight.dto;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Sanitized evidence for a document field. This DTO intentionally accepts only
 * the extraction audit allow-list; nearby OCR text is never part of the public
 * contract.
 */
public record DocumentFieldCandidate(
		String field,
		@JsonIgnore String rawValue,
		String normalizedValue,
		Source source,
		double confidence,
		Integer page,
		BoundingBox boundingBox,
		AnchorType nearbyAnchor,
		Integer segmentIndex,
		Disposition disposition,
		String reason) {

	public static final Set<String> ALLOWED_FIELDS = Set.of(
			"flight_number", "airline_name", "departure_date", "departure_time",
			"arrival_time", "departure_airport", "arrival_airport",
			"departure_terminal", "arrival_terminal");

	public enum Source {
		UNDERSTAND,
		STRUCTURE
	}

	public enum AnchorType {
		FLIGHT_CARD,
		AIRLINE_NAME,
		DATE_HEADER,
		DEPARTURE_NODE,
		ARRIVAL_NODE,
		DEPARTURE_TIME,
		ARRIVAL_TIME,
		DEPARTURE_TERMINAL,
		ARRIVAL_TERMINAL,
		UNKNOWN
	}

	public enum Disposition {
		SELECTED,
		MERGED,
		REPLACED,
		CANDIDATE,
		IGNORED
	}

	public DocumentFieldCandidate {
		field = normalizeField(field);
		if (!ALLOWED_FIELDS.contains(field)) {
			throw new IllegalArgumentException("Unsupported document evidence field");
		}
		rawValue = safeValue(rawValue);
		normalizedValue = safeValue(normalizedValue);
		source = Objects.requireNonNull(source, "source");
		confidence = Double.isFinite(confidence)
				? Math.max(0.0d, Math.min(1.0d, confidence)) : 0.0d;
		page = page == null || page < 0 ? null : page;
		nearbyAnchor = Objects.requireNonNullElse(nearbyAnchor, AnchorType.UNKNOWN);
		segmentIndex = segmentIndex == null || segmentIndex < 0 ? null : segmentIndex;
		disposition = Objects.requireNonNullElse(disposition, Disposition.CANDIDATE);
		reason = safeReason(reason);
	}

	public DocumentFieldCandidate withDisposition(Disposition nextDisposition, String nextReason) {
		return new DocumentFieldCandidate(field, rawValue, normalizedValue, source, confidence,
				page, boundingBox, nearbyAnchor, segmentIndex, nextDisposition, nextReason);
	}

	/**
	 * Stable public review-candidate name. Keep {@code normalizedValue} for
	 * backwards compatibility while exposing the concise value/source/confidence
	 * contract expected by review clients.
	 */
	@JsonProperty("value")
	public String value() {
		return normalizedValue;
	}

	private static String normalizeField(String value) {
		return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
	}

	private static String safeValue(String value) {
		if (value == null) return null;
		String normalized = value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ")
				.replaceAll("\\s+", " ").trim();
		if (normalized.isEmpty()) return null;
		return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
	}

	private static String safeReason(String value) {
		if (value == null || value.isBlank()) return "UNSPECIFIED";
		String normalized = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
		return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
	}
}
