package com.selene.onestepflight.service;

public class ExternalServiceNotConfiguredException extends RuntimeException {

	private final String service;

	public ExternalServiceNotConfiguredException(String service) {
		super(service + " is not configured");
		this.service = service;
	}

	public String service() {
		return service;
	}
}
