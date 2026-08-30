package com.selene.onestepflight.service;

public class InvalidDemoSessionIdException extends RuntimeException {
	public InvalidDemoSessionIdException() {
		super("demoSessionId must be a canonical UUID v4");
	}
}
