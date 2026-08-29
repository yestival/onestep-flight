package com.selene.onestepflight.service;

import java.util.List;

public record IdentityDecision(AirportIdentity canonicalAirport, IdentityStatus status,
		IdentityConfidence confidence, AirportIdentityCandidate selectedPlace,
		List<DomainEvidence> domains, List<AirportIdentityCandidate> candidates,
		List<String> reasons) {
	public IdentityDecision {
		domains = domains == null ? List.of() : List.copyOf(domains);
		candidates = candidates == null ? List.of() : List.copyOf(candidates);
		reasons = reasons == null ? List.of() : List.copyOf(reasons);
	}

	public List<DomainEvidence> verifiedDomains() {
		return domains.stream().filter(DomainEvidence::verified).toList();
	}
}
