package com.selene.onestepflight.service;

import java.util.List;

/** Atomic contact candidate: phone, role, page and source stay together. */
public record AirportContactCandidate(String candidateId, String airportId, String organization,
		PageAudience audience, ContactRole role, AirportPhoneCandidate phoneCandidate,
		String contactUrl, String sourceUrl, String sourceTitle, String sourceType,
		List<ResourceEvidence> evidence, IdentityConfidence confidence, String sourceJsonPath,
		String extractionMode, String context, String referenceTitle, String referenceSnippet,
		String structuralBindingPath, List<String> provenanceSignals) {
	public AirportContactCandidate {
		audience = audience == null ? PageAudience.UNKNOWN : audience;
		role = role == null ? ContactRole.UNKNOWN : role;
		evidence = evidence == null ? List.of() : List.copyOf(evidence);
		confidence = confidence == null ? IdentityConfidence.NONE : confidence;
		provenanceSignals = provenanceSignals == null ? List.of() : List.copyOf(provenanceSignals);
	}
	public AirportContactCandidate(String candidateId, String airportId, String organization,
			PageAudience audience, ContactRole role, AirportPhoneCandidate phoneCandidate,
			String contactUrl, String sourceUrl, String sourceTitle, String sourceType,
			List<ResourceEvidence> evidence, IdentityConfidence confidence) {
		this(candidateId, airportId, organization, audience, role, phoneCandidate, contactUrl,
				sourceUrl, sourceTitle, sourceType, evidence, confidence, null, null, null,
				null, null, null, List.of());
	}

	public boolean hasPassengerPage() {
		return audience == PageAudience.PASSENGER_GENERAL && contactUrl != null;
	}
}
