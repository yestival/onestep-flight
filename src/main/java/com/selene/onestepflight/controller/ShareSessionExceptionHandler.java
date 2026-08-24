package com.selene.onestepflight.controller;

import java.time.Instant;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.selene.onestepflight.dto.ShareSessionError;
import com.selene.onestepflight.service.ShareSessionAccessException;

import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice(assignableTypes = ShareSessionController.class)
public class ShareSessionExceptionHandler {

	@ExceptionHandler(ShareSessionAccessException.class)
	public ResponseEntity<ShareSessionError> handle(ShareSessionAccessException exception) {
		return switch (exception.reason()) {
			case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "SHARE_SESSION_NOT_FOUND",
					"This shared journey does not exist.");
			case INVALID_UPDATE_TOKEN -> error(HttpStatus.FORBIDDEN, "SHARE_SESSION_UPDATE_FORBIDDEN",
					"The update token is not valid for this shared journey.");
			case EXPIRED -> error(HttpStatus.GONE, "SHARE_SESSION_EXPIRED",
					"This shared journey has expired.");
			case STOPPED -> error(HttpStatus.GONE, "SHARE_SESSION_STOPPED",
					"Journey sharing has been stopped.");
		};
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ShareSessionError> handleInvalidTokenShape() {
		return error(HttpStatus.BAD_REQUEST, "SHARE_SESSION_TOKEN_INVALID",
				"The share-session token format is not valid.");
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	public ResponseEntity<ShareSessionError> handleConcurrentUpdate() {
		return error(HttpStatus.CONFLICT, "SHARE_SESSION_UPDATE_CONFLICT",
				"The shared journey changed at the same time. Send the current state again.");
	}

	private ResponseEntity<ShareSessionError> error(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(new ShareSessionError(code, message, Instant.now()));
	}
}
