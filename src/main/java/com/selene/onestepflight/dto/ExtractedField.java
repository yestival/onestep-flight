package com.selene.onestepflight.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record ExtractedField(
		String value,
		@JsonIgnore
		String rawValue,
		double confidence,
		Integer page,
		BoundingBox boundingBox,
		String sourceLabel) {

	public ExtractedField(String value, double confidence, Integer page,
			BoundingBox boundingBox, String sourceLabel) {
		this(value, value, confidence, page, boundingBox, sourceLabel);
	}

	public ExtractedField {
		rawValue = rawValue == null || rawValue.isBlank() ? null : rawValue.trim();
	}
}
