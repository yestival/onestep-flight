package com.selene.onestepflight.dto;

import java.time.Instant;

public record JourneyProblemResponse(String answer, AnswerStatus status, Instant retrievedAt) {
	public enum AnswerStatus { ANSWER, FALLBACK }
}
