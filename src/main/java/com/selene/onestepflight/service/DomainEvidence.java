package com.selene.onestepflight.service;

import java.util.List;

public record DomainEvidence(String host, String rawUrl, DomainAuthority authority, String source,
		String airportId, List<String> evidence) {
	public DomainEvidence { evidence = evidence == null ? List.of() : List.copyOf(evidence); }
	public boolean verified() {
		return authority == DomainAuthority.VERIFIED_STRUCTURED_PLACE
				|| authority == DomainAuthority.VERIFIED_CROSS_SOURCE
				|| authority == DomainAuthority.VERIFIED_STRUCTURED_ENTITY;
	}
}
