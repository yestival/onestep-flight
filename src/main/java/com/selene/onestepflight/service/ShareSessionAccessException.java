package com.selene.onestepflight.service;

public class ShareSessionAccessException extends RuntimeException {

	public enum Reason {
		NOT_FOUND,
		INVALID_UPDATE_TOKEN,
		EXPIRED,
		STOPPED
	}

	private final Reason reason;

	ShareSessionAccessException(Reason reason) {
		super(reason.name());
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
