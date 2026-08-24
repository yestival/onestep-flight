package com.selene.onestepflight.service;

public class InvalidDocumentException extends RuntimeException {

	private final String code;

	public InvalidDocumentException(String code) {
		super(code);
		this.code = code;
	}

	public String code() {
		return code;
	}
}
