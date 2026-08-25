package com.selene.onestepflight.dto;

/**
 * Requests a best-effort date lookup for one already extracted document leg.
 * The document DTO is privacy-whitelisted and lets the server apply the same
 * confidence/review rules used by the normal flight-resolution path.
 */
public record FlightDateDiscoveryRequest(
		String flightNumber,
		BoardingPassExtractionResponse document,
		Integer segmentIndex) {
}
