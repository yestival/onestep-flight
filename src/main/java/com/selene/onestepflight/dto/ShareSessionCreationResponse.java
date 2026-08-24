package com.selene.onestepflight.dto;

import java.time.Instant;

public record ShareSessionCreationResponse(
		String viewToken,
		String updateToken,
		String viewUrl,
		Instant expiresAt) {

	@Override
	public String toString() {
		return "ShareSessionCreationResponse[viewToken=[REDACTED], updateToken=[REDACTED], viewUrl=[REDACTED], expiresAt="
				+ expiresAt + "]";
	}
}
