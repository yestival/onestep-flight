package com.selene.onestepflight.dto;

import java.time.LocalDate;

public record FlightDateDiscoveryResponse(LocalDate departureDate, boolean dateRequired) {

	public static FlightDateDiscoveryResponse found(LocalDate departureDate) {
		return new FlightDateDiscoveryResponse(departureDate, false);
	}

	public static FlightDateDiscoveryResponse required() {
		return new FlightDateDiscoveryResponse(null, true);
	}
}
