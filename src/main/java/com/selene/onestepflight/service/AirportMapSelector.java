package com.selene.onestepflight.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import com.selene.onestepflight.dto.AirportResourcesResponse.MapResultType;
import com.selene.onestepflight.dto.AirportResourcesResponse.MapStatus;

/** Fixed-priority map selector with separate valid alternatives and invalid candidates. */
public final class AirportMapSelector {
	private final AirportMapCandidateClassifier classifier = new AirportMapCandidateClassifier();

	public Selection select(List<AirportMapCandidate> candidates, AirportMapCandidate mapsFallback) {
		List<Decision> valid = new ArrayList<>();
		List<Decision> invalid = new ArrayList<>();
		for (AirportMapCandidate candidate : candidates == null ? List.<AirportMapCandidate>of() : candidates) {
			AirportMapCandidateClassifier.Classification classification = classifier.classify(candidate.url(),
					candidate.title(), candidate.snippet(), candidate.linkText(), candidate.terminal());
			String externalReason = candidate.targetAirportMatched() ? externalWithoutOfficialChain(candidate)
					: "MAP_CANDIDATE_FOR_DIFFERENT_AIRPORT";
			if (externalReason != null) classification = AirportMapCandidateClassifier.Classification.rejected(externalReason);
			Decision decision = new Decision(candidate, classification,
					classification.accepted() ? AirportIdentityCandidate.Decision.VALID_NOT_SELECTED
							: AirportIdentityCandidate.Decision.REJECTED,
					classification.accepted() ? null : classification.rejectionType());
			if (classification.accepted()) valid.add(decision); else invalid.add(decision);
		}
		if (mapsFallback != null) {
			var fallbackClassification = new AirportMapCandidateClassifier.Classification(MapStatus.AIRPORT_LOCATION,
					MapResultType.GOOGLE_MAPS_AIRPORT_LOCATION, 10, 0, null, List.of(), List.of());
			valid.add(new Decision(mapsFallback, fallbackClassification,
					AirportIdentityCandidate.Decision.VALID_NOT_SELECTED, null));
		}
		valid.sort((left, right) -> -compareForSelection(left, right));
		Decision selected = valid.isEmpty() ? null : valid.get(0);
		List<Decision> alternatives = new ArrayList<>();
		for (Decision decision : valid) {
			if (decision == selected) continue;
			alternatives.add(decision);
		}
		if (selected != null) selected = new Decision(selected.candidate(), selected.classification(),
				AirportIdentityCandidate.Decision.ACCEPTED, "HIGHEST_PRIORITY_VALID_MAP");
		return new Selection(selected, List.copyOf(alternatives), List.copyOf(invalid));
	}

	private static String externalWithoutOfficialChain(AirportMapCandidate candidate) {
		if (candidate.airportOfficialDomain()) return null;
		URI uri = OfficialTravelSourcePolicy.safeUri(candidate.url());
		if (uri == null) return "UNSAFE_OR_NON_HTTPS_URL";
		return candidate.officialLinkChain() ? null : "EXTERNAL_MAP_WITHOUT_OFFICIAL_LINK_CHAIN";
	}

	private static int compareForSelection(Decision left, Decision right) {
		int priority = Integer.compare(selectionPriority(left), selectionPriority(right));
		return priority != 0 ? priority
				: Integer.compare(left.classification().withinCategoryScore(), right.classification().withinCategoryScore());
	}

	private static int selectionPriority(Decision decision) {
		if (!decision.candidate().airportOfficialDomain()
				&& decision.classification().resultType() != MapResultType.GOOGLE_MAPS_AIRPORT_LOCATION) return 20;
		return decision.classification().basePriority();
	}

	public record Decision(AirportMapCandidate candidate,
			AirportMapCandidateClassifier.Classification classification,
			AirportIdentityCandidate.Decision decision, String reason) { }

	public record Selection(Decision selectedCandidate, List<Decision> validAlternatives,
			List<Decision> invalidCandidates) {
		public Selection {
			validAlternatives = validAlternatives == null ? List.of() : List.copyOf(validAlternatives);
			invalidCandidates = invalidCandidates == null ? List.of() : List.copyOf(invalidCandidates);
		}
	}
}
