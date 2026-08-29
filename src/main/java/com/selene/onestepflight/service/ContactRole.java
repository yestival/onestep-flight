package com.selene.onestepflight.service;

public enum ContactRole {
	GENERAL_PASSENGER,
	AIRPORT_SWITCHBOARD,
	PASSENGER_INFORMATION,
	SPECIALIZED,
	CORPORATE,
	UNKNOWN;

	public boolean generalPassenger() {
		return this == GENERAL_PASSENGER || this == AIRPORT_SWITCHBOARD || this == PASSENGER_INFORMATION;
	}
}
