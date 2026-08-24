package com.selene.onestepflight.dto;

public record ExtractedField(
		String value,
		double confidence,
		Integer page,
		BoundingBox boundingBox,
		String sourceLabel) {
}
