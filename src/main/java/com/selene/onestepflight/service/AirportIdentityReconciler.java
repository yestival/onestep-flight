package com.selene.onestepflight.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Combines retained Search and Maps evidence; neither source is a trust root by itself. */
public final class AirportIdentityReconciler {
	public IdentityDecision reconcile(AirportIdentity canonical,
			List<DomainEvidence> searchDomains, List<AirportIdentityCandidate> searchCandidates,
			SerpApiGoogleMapsAirportPlaceParser.Result maps, boolean searchError, boolean mapsError) {
		List<DomainEvidence> evidence = new ArrayList<>();
		Map<String, DomainEvidence> mapsByHost = new LinkedHashMap<>();
		for (DomainEvidence item : maps.domainEvidence()) {
			evidence.add(item);
			if (item.host() != null) mapsByHost.put(normalizeHost(item.host()), item);
		}
		for (DomainEvidence item : searchDomains == null ? List.<DomainEvidence>of() : searchDomains) {
			String host = normalizeHost(item.host());
			if (mapsByHost.containsKey(host)) {
				evidence.add(new DomainEvidence(host, item.rawUrl(), DomainAuthority.VERIFIED_CROSS_SOURCE,
						item.source(), canonical.canonicalId(), combine(item.evidence(), List.of("SAME_HOST_AS_STRONG_MAPS_PLACE"))));
			} else evidence.add(item);
		}

		boolean structuredEntity = evidence.stream()
				.anyMatch(item -> item.authority() == DomainAuthority.VERIFIED_STRUCTURED_ENTITY);
		IdentityStatus status;
		IdentityConfidence confidence;
		if (maps.matchedAirport()) {
			status = IdentityStatus.VERIFIED;
			confidence = maps.selectedPlace() == null ? IdentityConfidence.HIGH : maps.selectedPlace().confidence();
		} else if (structuredEntity) {
			status = IdentityStatus.VERIFIED;
			confidence = IdentityConfidence.HIGH;
		} else if (evidence.stream().anyMatch(item -> item.authority() == DomainAuthority.PROVISIONAL_SEARCH)) {
			status = IdentityStatus.PARTIAL;
			confidence = IdentityConfidence.LOW;
		} else if (searchError || mapsError) {
			status = IdentityStatus.UPSTREAM_ERROR;
			confidence = IdentityConfidence.NONE;
		} else {
			status = IdentityStatus.UNVERIFIED;
			confidence = IdentityConfidence.NONE;
		}
		List<AirportIdentityCandidate> candidates = new ArrayList<>();
		if (searchCandidates != null) candidates.addAll(searchCandidates);
		candidates.addAll(maps.candidates());
		LinkedHashSet<String> reasons = new LinkedHashSet<>();
		if (status == IdentityStatus.PARTIAL) reasons.add("ONLY_PROVISIONAL_SEARCH_IDENTITY");
		if (status == IdentityStatus.UNVERIFIED) reasons.add("CANONICAL_AIRPORT_NOT_VERIFIED");
		if (status == IdentityStatus.UPSTREAM_ERROR) reasons.add("IDENTITY_UPSTREAM_ERROR");
		return new IdentityDecision(canonical, status, confidence, maps.selectedPlace(),
				List.copyOf(evidence), List.copyOf(candidates), List.copyOf(reasons));
	}

	private static List<String> combine(List<String> left, List<String> right) {
		LinkedHashSet<String> result = new LinkedHashSet<>(left == null ? List.of() : left);
		result.addAll(right);
		return List.copyOf(result);
	}

	private static String normalizeHost(String host) {
		if (host == null) return "";
		return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
	}
}
