package com.selene.onestepflight.service;

import java.text.Normalizer;
import java.util.Objects;
import java.util.regex.Pattern;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

/** Region-aware phone parsing and equivalence based on the canonical airport country. */
public final class AirportPhoneNormalizer {
	private static final Pattern SHORT_CODE = Pattern.compile("^[0-9]{3,6}$");
	private static final Pattern DATE_OR_HOURS = Pattern.compile(
			"^(?:(?:19|20)\\d{2}|\\d{1,4}[./-]\\d{1,2}[./-]\\d{1,4}|\\d{1,2}[:.]\\d{2}[-–—]\\d{1,2}[:.]\\d{2})$");
	private final PhoneNumberUtil phoneUtil;

	public AirportPhoneNormalizer() { this(PhoneNumberUtil.getInstance()); }
	AirportPhoneNormalizer(PhoneNumberUtil phoneUtil) { this.phoneUtil = Objects.requireNonNull(phoneUtil); }

	public AirportPhoneCandidate normalize(String raw, AirportIdentity airport, ContactRole role,
			String sourceUrl, String sourceType, PhoneEvidenceTier tier) {
		String display = clean(raw);
		if (display == null || DATE_OR_HOURS.matcher(display.replace(" ", "")).matches()) {
			return rejected(raw, airport, role, sourceUrl, sourceType, tier);
		}
		String digits = display.replaceAll("\\D", "");
		if (SHORT_CODE.matcher(digits).matches() && !display.startsWith("+")) {
			return rejected(raw, airport, role, sourceUrl, sourceType, tier);
		}
		try {
			PhoneNumber parsed = phoneUtil.parse(display, airport.isoCountry());
			if (!phoneUtil.isPossibleNumber(parsed) || !phoneUtil.isValidNumber(parsed)) {
				return rejected(raw, airport, role, sourceUrl, sourceType, tier);
			}
			String e164 = phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
			String national = Long.toString(parsed.getNationalNumber());
			PhoneScope scope = display.startsWith("+") ? PhoneScope.INTERNATIONAL : PhoneScope.NATIONAL;
			return new AirportPhoneCandidate(display, airport.isoCountry(), e164, national, "tel:" + e164,
					scope, role, sourceUrl, sourceType, airport.canonicalId(), tier, true);
		} catch (NumberParseException exception) {
			return rejected(raw, airport, role, sourceUrl, sourceType, tier);
		}
	}

	public AirportPhoneCandidate normalizeShortCode(String raw, AirportIdentity airport, ContactRole role,
			String sourceUrl, String sourceType, PhoneEvidenceTier tier,
			boolean verifiedPassengerPage) {
		String display = clean(raw);
		String digits = display == null ? "" : display.replaceAll("\\D", "");
		if (!verifiedPassengerPage || !role.generalPassenger() || sourceUrl == null
				|| !sourceUrl.startsWith("https://") || !SHORT_CODE.matcher(digits).matches()) {
			return rejected(raw, airport, role, sourceUrl, sourceType, tier);
		}
		return new AirportPhoneCandidate(display, airport.isoCountry(), null, digits, "tel:" + digits,
				PhoneScope.NATIONAL_SHORT_CODE, role, sourceUrl, sourceType, airport.canonicalId(), tier, true);
	}

	public PhoneEquivalence compare(AirportPhoneCandidate left, AirportPhoneCandidate right) {
		if (left == null || right == null || !left.accepted() || !right.accepted()) return PhoneEquivalence.UNKNOWN;
		if (left.e164() != null && left.e164().equals(right.e164())) return PhoneEquivalence.EQUIVALENT;
		if (left.scope() == PhoneScope.NATIONAL_SHORT_CODE ^ right.scope() == PhoneScope.NATIONAL_SHORT_CODE) {
			return left.airportId().equals(right.airportId()) && left.role().generalPassenger()
					&& right.role().generalPassenger()
					? PhoneEquivalence.COMPATIBLE_DIFFERENT_SCOPE : PhoneEquivalence.UNKNOWN;
		}
		if (left.e164() != null && right.e164() != null) return PhoneEquivalence.CONFLICT;
		return PhoneEquivalence.UNKNOWN;
	}

	private static AirportPhoneCandidate rejected(String raw, AirportIdentity airport, ContactRole role,
			String sourceUrl, String sourceType, PhoneEvidenceTier tier) {
		return new AirportPhoneCandidate(raw, airport == null ? null : airport.isoCountry(), null, null, null,
				PhoneScope.UNKNOWN, role == null ? ContactRole.UNKNOWN : role, sourceUrl, sourceType,
				airport == null ? null : airport.canonicalId(), tier == null ? PhoneEvidenceTier.UNVERIFIED : tier, false);
	}

	private static String clean(String raw) {
		if (raw == null || raw.isBlank()) return null;
		String value = Normalizer.normalize(raw, Normalizer.Form.NFKC)
				.replaceAll("[\\p{Cc}\\p{Cf}]", "").replaceAll("\\s+", " ").trim();
		value = value.replaceFirst("(?i)^(?:phone|telephone|tel)\\s*[:=\\-]?\\s*", "").trim();
		return value.matches("^\\+?[0-9() .\\-]{3,28}$") ? value : null;
	}
}
