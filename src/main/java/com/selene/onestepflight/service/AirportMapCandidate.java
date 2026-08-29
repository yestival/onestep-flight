package com.selene.onestepflight.service;

import java.util.List;

public record AirportMapCandidate(String candidateId, String url, String title, String snippet,
		String linkText, String sourceUrl, boolean airportOfficialDomain, boolean officialLinkChain,
		String airportId, String terminal, String sourceType, String normalizedUrl,
		String sourceJsonPath, String extractionMode, String context, String referenceTitle,
		String referenceSnippet, String structuralBindingPath, boolean targetAirportMatched,
		List<String> provenanceSignals) {
	public AirportMapCandidate {
		normalizedUrl = normalizedUrl == null ? AiResponseCandidateExtractor.normalizeUrl(url) : normalizedUrl;
		provenanceSignals = provenanceSignals == null ? List.of() : List.copyOf(provenanceSignals);
	}

	public AirportMapCandidate(String candidateId, String url, String title, String snippet,
			String linkText, String sourceUrl, boolean airportOfficialDomain, boolean officialLinkChain,
			String airportId, String terminal, String sourceType) {
		this(candidateId, url, title, snippet, linkText, sourceUrl, airportOfficialDomain,
				officialLinkChain, airportId, terminal, sourceType, null, null, null,
				null, null, null, null, true, List.of());
	}
}
