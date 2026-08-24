package com.selene.onestepflight.service;

public class InvalidFlightNumberException extends RuntimeException {

	public InvalidFlightNumberException() {
		super("Invalid flight number");
	}
}
