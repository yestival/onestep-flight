package com.selene.onestepflight.dto;

import java.time.Instant;

public record ShareSessionError(String code, String message, Instant timestamp) {
}
