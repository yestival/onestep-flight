package com.selene.onestepflight.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/** Evidence-ranked, provenance-preserving contact selection. */
public final class AirportContactSelector {
	private final AirportPhoneNormalizer phoneNormalizer;

	public AirportContactSelector(AirportPhoneNormalizer phoneNormalizer) {
		this.phoneNormalizer = phoneNormalizer;
	}

	public Selection select(List<AirportContactCandidate> candidates) {
		List<AirportContactCandidate> safe = (candidates == null ? List.<AirportContactCandidate>of() : candidates)
				.stream().filter(this::safeCandidate).sorted(Comparator.comparingInt(this::rank).reversed()).toList();
		List<AirportContactCandidate> officialPhones = safe.stream()
				.filter(candidate -> candidate.phoneCandidate() != null && candidate.phoneCandidate().accepted()).toList();
		AirportContactCandidate selectedPhone = selectPhone(officialPhones);
		if (selectedPhone == null && hasSameTierConflict(officialPhones)) {
			return new Selection(null, null, List.of(), true, List.of("EQUAL_TIER_OFFICIAL_PHONE_CONFLICT"));
		}
		List<AirportPhoneCandidate> alternatives = new ArrayList<>();
		if (selectedPhone != null) {
			for (AirportContactCandidate candidate : officialPhones) {
				if (candidate == selectedPhone) continue;
				PhoneEquivalence relation = phoneNormalizer.compare(selectedPhone.phoneCandidate(), candidate.phoneCandidate());
				if (relation == PhoneEquivalence.COMPATIBLE_DIFFERENT_SCOPE) alternatives.add(candidate.phoneCandidate());
			}
		}
		AirportContactCandidate selectedPage = safe.stream().filter(AirportContactCandidate::hasPassengerPage)
				.filter(candidate -> pageCompatible(selectedPhone, candidate)).findFirst().orElse(null);
		boolean lowerTierConflict = selectedPhone != null && officialPhones.stream().filter(candidate -> candidate != selectedPhone)
				.anyMatch(candidate -> phoneNormalizer.compare(selectedPhone.phoneCandidate(), candidate.phoneCandidate())
						== PhoneEquivalence.CONFLICT);
		return new Selection(selectedPhone == null ? null : selectedPhone.phoneCandidate(), selectedPage,
				List.copyOf(alternatives), lowerTierConflict,
				lowerTierConflict ? List.of("LOWER_TIER_PHONE_CONFLICT") : List.of());
	}

	private AirportContactCandidate selectPhone(List<AirportContactCandidate> phones) {
		if (phones.isEmpty()) return null;
		int topRank = rank(phones.get(0));
		List<AirportContactCandidate> top = phones.stream().filter(candidate -> rank(candidate) == topRank).toList();
		if (hasTrueConflict(top)) return null;
		return top.get(0);
	}

	private boolean hasSameTierConflict(List<AirportContactCandidate> phones) {
		if (phones.size() < 2) return false;
		int topRank = rank(phones.get(0));
		return hasTrueConflict(phones.stream().filter(candidate -> rank(candidate) == topRank).toList());
	}

	private boolean hasTrueConflict(List<AirportContactCandidate> values) {
		for (int left = 0; left < values.size(); left++) {
			for (int right = left + 1; right < values.size(); right++) {
				if (phoneNormalizer.compare(values.get(left).phoneCandidate(), values.get(right).phoneCandidate())
						== PhoneEquivalence.CONFLICT) return true;
			}
		}
		return false;
	}

	private boolean pageCompatible(AirportContactCandidate phone, AirportContactCandidate page) {
		if (phone == null) return true;
		if (phone == page) return true;
		if (sameUrl(phone.sourceUrl(), page.contactUrl())) return true;
		if (page.phoneCandidate() != null && page.phoneCandidate().accepted()) {
			PhoneEquivalence relation = phoneNormalizer.compare(phone.phoneCandidate(), page.phoneCandidate());
			return relation == PhoneEquivalence.EQUIVALENT
					|| relation == PhoneEquivalence.COMPATIBLE_DIFFERENT_SCOPE;
		}
		if (phone.phoneCandidate() != null
				&& phone.phoneCandidate().evidenceTier() == PhoneEvidenceTier.VERIFIED_STRUCTURED_MAPS
				&& page.audience() == PageAudience.PASSENGER_GENERAL
				&& phone.airportId().equals(page.airportId())) return true;
		return false;
	}

	private boolean safeCandidate(AirportContactCandidate candidate) {
		if (candidate == null || candidate.airportId() == null) return false;
		if (candidate.phoneCandidate() != null && candidate.phoneCandidate().sourceUrl() != null
				&& !sameUrl(candidate.phoneCandidate().sourceUrl(), candidate.sourceUrl())) return false;
		if (candidate.contactUrl() != null && OfficialTravelSourcePolicy.safeUri(candidate.contactUrl()) == null) return false;
		if (candidate.contactUrl() != null && !candidate.evidence().stream().anyMatch(ResourceEvidence::candidateLevelBound)) {
			return false;
		}
		return candidate.audience() != PageAudience.CORPORATE_SPECIALIZED
				&& candidate.audience() != PageAudience.THIRD_PARTY_DIRECTORY
				&& candidate.audience() != PageAudience.PASSENGER_SPECIALIZED;
	}

	private int rank(AirportContactCandidate candidate) {
		AirportPhoneCandidate phone = candidate.phoneCandidate();
		if (phone == null) return candidate.audience() == PageAudience.PASSENGER_GENERAL ? 10 : 0;
		return switch (phone.evidenceTier()) {
			case TWO_OFFICIAL_SOURCES -> 60;
			case VERIFIED_PASSENGER_PAGE -> 50;
			case VERIFIED_STRUCTURED_MAPS -> 40;
			case VERIFIED_STRUCTURED_ENTITY -> 35;
			case AI_BOUND_DISCOVERY -> 20;
			case UNVERIFIED -> 0;
		};
	}

	private static boolean sameUrl(String left, String right) {
		try {
			if (left == null || right == null) return false;
			URI first = URI.create(left).normalize();
			URI second = URI.create(right).normalize();
			return normalizedHost(first).equals(normalizedHost(second))
					&& normalizedPath(first).equals(normalizedPath(second));
		} catch (IllegalArgumentException exception) { return false; }
	}

	private static String normalizedHost(URI uri) {
		return uri.getHost() == null ? "" : uri.getHost().toLowerCase().replaceFirst("^www\\.", "");
	}

	private static String normalizedPath(URI uri) {
		String path = uri.getPath();
		return path == null || path.isBlank() ? "/" : path.replaceFirst("/+$", "");
	}

	public record Selection(AirportPhoneCandidate phone, AirportContactCandidate contact,
			List<AirportPhoneCandidate> alternatives, boolean conflict, List<String> reasons) {
		public Selection {
			alternatives = alternatives == null ? List.of() : List.copyOf(new LinkedHashSet<>(alternatives));
			reasons = reasons == null ? List.of() : List.copyOf(reasons);
		}
	}
}
