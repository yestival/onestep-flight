package com.selene.onestepflight.service;

import java.time.LocalDate;

/** Provider boundary for a flight-number lookup that does not require a date. */
public interface FlightDateDiscoveryProvider {

	LocalDate discoverDepartureDate(String flightNumber, String departureAirportHint,
			String arrivalAirportHint, String scheduledDepartureHint);
}
