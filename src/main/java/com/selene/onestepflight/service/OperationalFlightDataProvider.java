package com.selene.onestepflight.service;

import java.time.LocalDate;

import com.selene.onestepflight.dto.ProviderFlightResult;

/** Boundary for one real operational-flight provider. Implementations never use fixtures. */
public interface OperationalFlightDataProvider {

	String providerName();

	ProviderFlightResult findFlight(String flightNumber, LocalDate departureDate,
			String departureAirportHint, String arrivalAirportHint, boolean refresh);

	default ProviderFlightResult findFlight(String flightNumber, LocalDate departureDate,
			String departureAirportHint, String arrivalAirportHint,
			String scheduledDepartureHint, boolean refresh) {
		return findFlight(flightNumber, departureDate, departureAirportHint, arrivalAirportHint, refresh);
	}
}
