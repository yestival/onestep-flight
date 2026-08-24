package com.selene.onestepflight.service;

import java.time.LocalDate;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.selene.onestepflight.dto.FlightResponse;

@Service
public class FlightService {

	private final DemoFlightDataProvider demoFlightDataProvider;
	private final LiveFlightDataProvider liveFlightDataProvider;

	public FlightService(DemoFlightDataProvider demoFlightDataProvider, LiveFlightDataProvider liveFlightDataProvider) {
		this.demoFlightDataProvider = demoFlightDataProvider;
		this.liveFlightDataProvider = liveFlightDataProvider;
	}

	public FlightResponse getFlight(String flightNumber) {
		return getFlight(flightNumber, null);
	}

	public FlightResponse getFlight(String flightNumber, LocalDate departureDate) {
		return getFlight(flightNumber, departureDate, true, false);
	}

	public FlightResponse getFlight(String flightNumber, LocalDate departureDate, boolean demo, boolean refresh) {
		String normalized = normalize(flightNumber);
		if (!demo) {
			return liveFlightDataProvider.findFlight(normalized, departureDate, refresh);
		}
		FlightResponse flight = demoFlightDataProvider.findByFlightNumber(normalized)
				.orElseThrow(() -> new FlightNotFoundException(normalized));
		if (departureDate != null && !departureDate.equals(flight.departureDate())) throw new FlightNotFoundException(normalized);
		return flight;
	}

	public String normalize(String flightNumber) {
		if (flightNumber == null) {
			throw new InvalidFlightNumberException();
		}

		String normalized = flightNumber.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
		if (!normalized.matches("[A-Z0-9]{2,3}\\d{1,4}")) {
			throw new InvalidFlightNumberException();
		}
		return normalized;
	}
}
