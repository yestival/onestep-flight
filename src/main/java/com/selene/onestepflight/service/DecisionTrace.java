package com.selene.onestepflight.service;

import java.time.Instant;
import java.util.List;

/** Sanitized production decisions; contains no API key and no complete upstream response body. */
public record DecisionTrace(String airportCode, String canonicalAirportId, String airportDatasetVersion,
		String identityVerifierVersion, IdentityStatus identityStatus, Instant startedAt, Instant completedAt,
		List<Entry> candidates, List<HttpStage> httpStages, List<AiStage> aiStages) {
	public DecisionTrace {
		candidates = candidates == null ? List.of() : List.copyOf(candidates);
		httpStages = httpStages == null ? List.of() : List.copyOf(httpStages);
		aiStages = aiStages == null ? List.of() : List.copyOf(aiStages);
	}
	public DecisionTrace(String airportCode, String canonicalAirportId, String airportDatasetVersion,
			String identityVerifierVersion, IdentityStatus identityStatus, Instant startedAt, Instant completedAt,
			List<Entry> candidates, List<HttpStage> httpStages) {
		this(airportCode, canonicalAirportId, airportDatasetVersion, identityVerifierVersion, identityStatus,
				startedAt, completedAt, candidates, httpStages, List.of());
	}

	public record Entry(String candidateId, String stage, String sourceJsonPath, String sourceType,
			String rawUrl, String rawPhone, String normalizedUrl, String normalizedPhone,
			String canonicalAirportId, List<String> identitySignals, DomainAuthority domainAuthority,
			String organizationScope, PageAudience pageAudience, String resourceRole,
			IdentityConfidence confidence, AirportIdentityCandidate.Decision decision,
			List<String> candidateSpecificReasons, List<String> selectedFields,
			Instant startedAt, Instant completedAt, String extractionMode, String context,
			String referenceTitle, String referenceSnippet, String structuralBindingPath,
			List<String> provenanceSignals) {
		public Entry {
			identitySignals = identitySignals == null ? List.of() : List.copyOf(identitySignals);
			candidateSpecificReasons = candidateSpecificReasons == null ? List.of() : List.copyOf(candidateSpecificReasons);
			selectedFields = selectedFields == null ? List.of() : List.copyOf(selectedFields);
			provenanceSignals = provenanceSignals == null ? List.of() : List.copyOf(provenanceSignals);
		}
		public Entry(String candidateId, String stage, String sourceJsonPath, String sourceType,
				String rawUrl, String rawPhone, String normalizedUrl, String normalizedPhone,
				String canonicalAirportId, List<String> identitySignals, DomainAuthority domainAuthority,
				String organizationScope, PageAudience pageAudience, String resourceRole,
				IdentityConfidence confidence, AirportIdentityCandidate.Decision decision,
				List<String> candidateSpecificReasons, List<String> selectedFields,
				Instant startedAt, Instant completedAt) {
			this(candidateId, stage, sourceJsonPath, sourceType, rawUrl, rawPhone, normalizedUrl,
					normalizedPhone, canonicalAirportId, identitySignals, domainAuthority, organizationScope,
					pageAudience, resourceRole, confidence, decision, candidateSpecificReasons, selectedFields,
					startedAt, completedAt, null, null, null, null, null, List.of());
		}
	}

	public record HttpStage(String stage, Integer httpStatus, String contentType, String providerRequestId,
			long responseLength, String safeBodyFingerprint, String error,
			Instant startedAt, Instant completedAt) { }

	public record AiStage(String stage, AiResponseCandidateExtractor.ResponseMode responseMode,
			boolean jsonParseAttempted, boolean jsonParsed, String jsonParseFailure,
			int strictJsonCandidates, int referenceCandidates, int snippetLinkCandidates,
			int markdownCandidates, int uniqueUrlCandidates, int selectedCandidates,
			List<String> diagnostics) {
		public AiStage { diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics); }
	}
}
