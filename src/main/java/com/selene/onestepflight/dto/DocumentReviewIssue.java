package com.selene.onestepflight.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A user-facing, actionable or advisory document extraction review item. */
public record DocumentReviewIssue(
		Integer segmentIndex,
		String field,
		Kind kind,
		boolean blocking,
		List<DocumentFieldCandidate> candidates,
		String selectedValue,
		String reason) {

	private static final java.util.Set<String> BLOCKING_FIELDS = java.util.Set.of(
			"flight_number", "departure_date");

	public enum Kind {
		ACTIONABLE,
		ADVISORY
	}

	public DocumentReviewIssue {
		boolean requestedBlocking = blocking;
		segmentIndex = segmentIndex == null || segmentIndex < 0 ? null : segmentIndex;
		field = field == null ? null : field.trim().toLowerCase(Locale.ROOT);
		if (!DocumentFieldCandidate.ALLOWED_FIELDS.contains(field)) {
			throw new IllegalArgumentException("Unsupported document review field");
		}
		final Integer safeSegmentIndex = segmentIndex;
		final String safeField = field;
		List<DocumentFieldCandidate> safeCandidates = candidates == null ? List.of()
				: candidates.stream().filter(java.util.Objects::nonNull)
						.filter(candidate -> safeField.equals(candidate.field()))
						.filter(candidate -> safeSegmentIndex == null
								|| candidate.segmentIndex() == null
								|| safeSegmentIndex.equals(candidate.segmentIndex()))
						.toList();
		Map<String, Boolean> distinct = new LinkedHashMap<>();
		for (DocumentFieldCandidate candidate : safeCandidates) {
			String value = candidate.normalizedValue();
			if (value != null) distinct.putIfAbsent(value, Boolean.TRUE);
		}
		candidates = List.copyOf(safeCandidates);
		selectedValue = safeValue(selectedValue);
		boolean unresolvedMultiple = selectedValue == null && distinct.size() >= 2;
		boolean explicitConflictWithoutCandidates = selectedValue == null
				&& safeCandidates.isEmpty() && requestedBlocking;
		blocking = BLOCKING_FIELDS.contains(field)
				&& (unresolvedMultiple || explicitConflictWithoutCandidates);
		kind = blocking ? Kind.ACTIONABLE : Kind.ADVISORY;
		reason = safeReason(reason);
	}

	private static String safeValue(String value) {
		if (value == null) return null;
		String normalized = value.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
		if (normalized.isEmpty()) return null;
		return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
	}

	private static String safeReason(String value) {
		if (value == null || value.isBlank()) return "UNSPECIFIED";
		String normalized = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
		return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
	}
}
