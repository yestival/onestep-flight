package com.selene.onestepflight.service;

public class FlightNotFoundException extends RuntimeException {

	public FlightNotFoundException(String flightNumber) {
		super("Flight not found: " + flightNumber);
	}
}
