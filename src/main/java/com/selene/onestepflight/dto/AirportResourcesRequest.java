package com.selene.onestepflight.dto;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AirportResourcesRequest(
		@NotBlank @jakarta.validation.constraints.Pattern(regexp = "[A-Z]{3}") String airportCode,
		@Size(max = 120) String airportName,
		@Size(max = 40) String terminal,
		boolean forceRefresh,
		RefreshTarget refreshTarget) {

	private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}]");
	private static final Pattern SAFE_DISPLAY = Pattern.compile("[\\p{L}\\p{N} .\\x27()&/\\-]+",
			Pattern.UNICODE_CHARACTER_CLASS);

	public AirportResourcesRequest {
		airportCode = airportCode == null ? null : Normalizer.normalize(airportCode.trim(), Normalizer.Form.NFKC)
				.toUpperCase(Locale.ROOT);
		airportName = safeDisplay(airportName);
		terminal = safeDisplay(terminal);
		refreshTarget = refreshTarget == null ? RefreshTarget.BOTH : refreshTarget;
	}

	public AirportResourcesRequest(String airportCode, String airportName, String terminal, boolean forceRefresh) {
		this(airportCode, airportName, terminal, forceRefresh, RefreshTarget.BOTH);
	}

	public boolean requestsMap() { return refreshTarget != RefreshTarget.CONTACT; }
	public boolean requestsContact() { return refreshTarget != RefreshTarget.MAP; }
	public boolean refreshesMap() { return forceRefresh && requestsMap(); }
	public boolean refreshesContact() { return forceRefresh && requestsContact(); }

	private static String safeDisplay(String value) {
		if (value == null) return null;
		String normalized = CONTROL.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC))
				.replaceAll("").replaceAll("\\s+", " ").trim();
		return normalized.isEmpty() || !SAFE_DISPLAY.matcher(normalized).matches() ? null : normalized;
	}

	public enum RefreshTarget { BOTH, MAP, CONTACT }
}
