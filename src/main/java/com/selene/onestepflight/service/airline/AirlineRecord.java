package com.selene.onestepflight.service.airline;

import java.time.LocalDate;

/**
 * One source row. Null validity dates mean the selected source did not publish
 * them; INACTIVE is deliberately not re-labelled as HISTORICAL.
 */
public record AirlineRecord(
		long sourceId,
		String name,
		String alias,
		String iata,
		String icao,
		String callsign,
		String country,
		AirlineStatus status,
		String sourceStatus,
		LocalDate validFrom,
		LocalDate validTo) {
}
