package com.selene.onestepflight.service;

import java.time.LocalDate;

import com.selene.onestepflight.dto.FlightResponse;

public interface LiveFlightDataProvider {

	FlightResponse findFlight(String flightNumber, LocalDate departureDate, boolean refresh);
}
