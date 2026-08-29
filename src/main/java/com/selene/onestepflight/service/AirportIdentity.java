package com.selene.onestepflight.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Canonical local airport entity generated from the pinned OurAirports dataset. */
public record AirportIdentity(String iataCode, String icaoCode, String name, String municipality,
		String isoCountry, String countryName, double latitude, double longitude, String type,
		boolean scheduledService, List<String> aliases) {
	public AirportIdentity {
		iataCode = required(iataCode, "iataCode").toUpperCase(Locale.ROOT);
		name = required(name, "name");
		isoCountry = required(isoCountry, "isoCountry").toUpperCase(Locale.ROOT);
		type = required(type, "type");
		if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
			throw new IllegalArgumentException("latitude is invalid for " + iataCode);
		}
		if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
			throw new IllegalArgumentException("longitude is invalid for " + iataCode);
		}
		aliases = aliases == null ? List.of() : List.copyOf(new LinkedHashSet<>(aliases));
	}

	public String canonicalId() {
		return iataCode + (icaoCode == null || icaoCode.isBlank() ? "" : ":" + icaoCode);
	}

	public String searchName() {
		return String.join(" ", List.of(name, value(municipality), value(countryName))).replaceAll("\\s+", " ").trim();
	}

	public AirportIdentity withRequestAlias(String requestedName) {
		if (requestedName == null || requestedName.isBlank()) return this;
		LinkedHashSet<String> values = new LinkedHashSet<>(aliases);
		values.add(requestedName.trim());
		return new AirportIdentity(iataCode, icaoCode, name, municipality, isoCountry, countryName,
				latitude, longitude, type, scheduledService, List.copyOf(values));
	}

	private static String required(String value, String field) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
		return value.trim();
	}

	private static String value(String value) { return value == null ? "" : value; }
}
