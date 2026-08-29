package com.selene.onestepflight.service;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Final format/safety gate for a value already identified as a phone by a dedicated field. */
final class AirportPhoneSafetyValidator {
	private static final Pattern ALLOWED = Pattern.compile("^\\+?[0-9() .\\-]{4,24}$");
	private static final Pattern YEAR_RANGE = Pattern.compile("^(?:19|20)\\d{2}\\s*[-–—]\\s*(?:19|20)\\d{2}$");
	private static final Pattern DATE = Pattern.compile("^\\d{1,4}[/.\\-]\\d{1,2}[/.\\-]\\d{1,4}$");
	private static final Pattern HOURS = Pattern.compile("^\\d{1,2}[:.]\\d{2}\\s*[-–—]\\s*\\d{1,2}[:.]\\d{2}$");

	Validation validate(String raw) {
		if (raw == null || raw.isBlank()) return Validation.rejected();
		String display = Normalizer.normalize(raw, Normalizer.Form.NFKC)
				.replaceAll("[\\p{Cc}\\p{Cf}]", "").replaceAll("\\s+", " ").trim();
		display = display.replaceFirst("(?i)^(?:phone|telephone|tel)\\s*[:=\\-]?\\s*", "").trim();
		if (!ALLOWED.matcher(display).matches() || YEAR_RANGE.matcher(display).matches()
				|| DATE.matcher(display).matches() || HOURS.matcher(display).matches()) return Validation.rejected();
		String digits = display.replaceAll("\\D", "");
		if (digits.length() < 5 || digits.length() > 18 || digits.matches("(?:19|20)\\d{2}")) {
			return Validation.rejected();
		}
		String tel = "tel:" + (display.startsWith("+") ? "+" : "") + digits;
		return new Validation(display, tel, digits, true);
	}

	record Validation(String display, String telUri, String normalizedDigits, boolean accepted) {
		static Validation rejected() { return new Validation(null, null, null, false); }
	}
}
