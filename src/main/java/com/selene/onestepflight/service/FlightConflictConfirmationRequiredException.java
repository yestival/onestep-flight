package com.selene.onestepflight.service;

public class FlightConflictConfirmationRequiredException extends RuntimeException {

	public FlightConflictConfirmationRequiredException() {
		super("A blocking flight-data conflict requires passenger confirmation");
	}
}
