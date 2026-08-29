package com.selene.onestepflight.dto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.selene.onestepflight.service.DecisionTrace;

/** Independently verified map and contact resources for one airport context. */
public record AirportResourcesResponse(
		String airportCode,
		String airportName,
		String terminal,
		String localAirportName,
		String countryOrRegion,
		MapResource map,
		ContactResource contact,
		int serpApiRequestCount,
		List<String> rejectionReasons,
		Instant retrievedAt,
		boolean fromCache,
		String identityStatus,
		DecisionTrace decisionTrace) {

	public AirportResourcesResponse {
		airportCode = Objects.requireNonNull(airportCode, "airportCode");
		airportName = Objects.requireNonNull(airportName, "airportName");
		map = Objects.requireNonNull(map, "map");
		contact = Objects.requireNonNull(contact, "contact");
		rejectionReasons = rejectionReasons == null ? List.of() : List.copyOf(rejectionReasons);
		retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
	}

	public AirportResourcesResponse(String airportCode, String airportName, String terminal,
			String localAirportName, String countryOrRegion, MapResource map, ContactResource contact,
			int serpApiRequestCount, List<String> rejectionReasons, Instant retrievedAt, boolean fromCache) {
		this(airportCode, airportName, terminal, localAirportName, countryOrRegion, map, contact,
				serpApiRequestCount, rejectionReasons, retrievedAt, fromCache, null, null);
	}

	public AirportResourcesResponse withCache(boolean mapCached, boolean contactCached) {
		return new AirportResourcesResponse(airportCode, airportName, terminal, localAirportName, countryOrRegion,
				map.withCache(mapCached), contact.withCache(contactCached),
				mapCached && contactCached ? 0 : serpApiRequestCount, rejectionReasons, retrievedAt,
				mapCached && contactCached, identityStatus, decisionTrace);
	}

	public boolean hasAnyResource() {
		return map.status() != MapStatus.NOT_FOUND && map.status() != MapStatus.TEMPORARILY_UNAVAILABLE
				|| contact.status() != ContactStatus.NOT_FOUND
						&& contact.status() != ContactStatus.TEMPORARILY_UNAVAILABLE;
	}

	public record MapResource(MapStatus status, String url, String sourceUrl, String sourceTitle,
			MapResultType resultType, String sourceLanguage, String mapScope, String verificationReason,
			List<String> rejectedCandidateTypes, boolean fromCache) {
		public MapResource {
			status = Objects.requireNonNull(status, "status");
			resultType = Objects.requireNonNull(resultType, "resultType");
			rejectedCandidateTypes = rejectedCandidateTypes == null ? List.of() : List.copyOf(rejectedCandidateTypes);
		}
		public MapResource withCache(boolean cached) {
			return new MapResource(status, url, sourceUrl, sourceTitle, resultType, sourceLanguage, mapScope,
					verificationReason, rejectedCandidateTypes, cached);
		}
		public static MapResource notFound() {
			return new MapResource(MapStatus.NOT_FOUND, null, null, null, MapResultType.NOT_FOUND,
					null, null, null, List.of(), false);
		}
		public static MapResource temporarilyUnavailable() {
			return new MapResource(MapStatus.TEMPORARILY_UNAVAILABLE, null, null, null,
					MapResultType.TEMPORARILY_UNAVAILABLE, null, null, null, List.of(), false);
		}
	}

	public record ContactResource(ContactStatus status, String phoneDisplay, String telUri,
			String contactUrl, String sourceUrl, String sourceTitle, String sourceLanguage, String sourceType,
			String phoneRole, String evidenceTier, boolean airportIdentityConfirmed,
			boolean aiDedicatedPhoneField, boolean mapsStructuredPhonePresent, boolean phoneConflict,
			boolean fromCache, boolean rawMapsPhonePresent, boolean acceptedMapsPhonePresent,
			List<AlternativePhone> alternativePhones) {
		public ContactResource {
			status = Objects.requireNonNull(status, "status");
			alternativePhones = alternativePhones == null ? List.of() : List.copyOf(alternativePhones);
		}
		public ContactResource(ContactStatus status, String phoneDisplay, String telUri,
				String contactUrl, String sourceUrl, String sourceTitle, String sourceLanguage, String sourceType,
				String phoneRole, String evidenceTier, boolean airportIdentityConfirmed,
				boolean aiDedicatedPhoneField, boolean mapsStructuredPhonePresent, boolean phoneConflict,
				boolean fromCache) {
			this(status, phoneDisplay, telUri, contactUrl, sourceUrl, sourceTitle, sourceLanguage, sourceType,
					phoneRole, evidenceTier, airportIdentityConfirmed, aiDedicatedPhoneField,
					mapsStructuredPhonePresent, phoneConflict, fromCache, mapsStructuredPhonePresent,
					mapsStructuredPhonePresent && phoneDisplay != null, List.of());
		}
		public ContactResource withCache(boolean cached) {
			return new ContactResource(status, phoneDisplay, telUri, contactUrl, sourceUrl, sourceTitle,
					sourceLanguage, sourceType, phoneRole, evidenceTier, airportIdentityConfirmed,
					aiDedicatedPhoneField, mapsStructuredPhonePresent, phoneConflict, cached,
					rawMapsPhonePresent, acceptedMapsPhonePresent, alternativePhones);
		}
		public static ContactResource notFound() {
			return empty(ContactStatus.NOT_FOUND);
		}
		public static ContactResource temporarilyUnavailable() {
			return empty(ContactStatus.TEMPORARILY_UNAVAILABLE);
		}
		private static ContactResource empty(ContactStatus status) {
			return new ContactResource(status, null, null, null, null, null, null, null, null, null,
					false, false, false, false, false, false, false, List.of());
		}
	}

	public record AlternativePhone(String phoneDisplay, String telUri, String scope, String sourceUrl) { }

	public enum MapStatus { FOUND, OFFICIAL_PAGE_ONLY, AIRPORT_LOCATION, NOT_FOUND, TEMPORARILY_UNAVAILABLE }
	public enum MapResultType {
		DIRECT_INTERACTIVE_MAP,
		DIRECT_MAP_DOCUMENT,
		OFFICIAL_MAP_LANDING_PAGE,
		GOOGLE_MAPS_AIRPORT_LOCATION,
		GUIDE_OR_GENERIC_PAGE,
		NOT_FOUND,
		TEMPORARILY_UNAVAILABLE
	}
	public enum ContactStatus { PHONE_FOUND, CONTACT_PAGE_ONLY, NOT_FOUND, TEMPORARILY_UNAVAILABLE }
}
