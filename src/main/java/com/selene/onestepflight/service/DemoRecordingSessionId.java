package com.selene.onestepflight.service;

import java.util.Locale;
import java.util.regex.Pattern;

public final class DemoRecordingSessionId {
	private static final Pattern UUID_V4 = Pattern.compile(
			"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

	private DemoRecordingSessionId() {
	}

	public static String normalize(String value) {
		if (value == null || value.isBlank()) return null;
		String candidate = value;
		return UUID_V4.matcher(candidate).matches() ? candidate.toLowerCase(Locale.ROOT) : null;
	}
}
