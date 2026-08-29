package com.selene.onestepflight.service;

public record AirportPhoneCandidate(String rawDisplay, String region, String e164, String nationalNumber,
		String telUri, PhoneScope scope, ContactRole role, String sourceUrl, String sourceType,
		String airportId, PhoneEvidenceTier evidenceTier, boolean accepted) {
	public String passengerDisplay() {
		return e164 == null ? rawDisplay : e164;
	}
}
