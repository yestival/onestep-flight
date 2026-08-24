package com.selene.onestepflight.service;

import java.util.Optional;

import com.selene.onestepflight.dto.FlightResponse;

/** Adapter boundary for a future real flight provider such as SerpApi. */
public interface FlightDataProvider {

	Optional<FlightResponse> findByFlightNumber(String flightNumber);
}
