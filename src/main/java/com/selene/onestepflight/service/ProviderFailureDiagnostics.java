package com.selene.onestepflight.service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Locale;

import javax.net.ssl.SSLException;

final class ProviderFailureDiagnostics {

	private static final int MAX_SAFE_MESSAGE_LENGTH = 240;

	private ProviderFailureDiagnostics() {
	}

	static Failure classify(String provider, Throwable exception) {
		Throwable root = deepestCause(exception);
		String message = safeMessage(root);
		String normalized = message.toLowerCase(Locale.ROOT);
		String category;
		String outcome = "UPSTREAM_ERROR";

		if (hasCause(exception, UnknownHostException.class)
				|| hasCause(exception, UnresolvedAddressException.class)) {
			category = "DNS_FAILED";
		} else if (hasCause(exception, HttpConnectTimeoutException.class)
				|| hasCause(exception, SocketTimeoutException.class) && normalized.contains("connect")) {
			category = "CONNECT_TIMEOUT";
			outcome = "TIMEOUT";
		} else if (hasCause(exception, HttpTimeoutException.class)
				|| hasCause(exception, SocketTimeoutException.class)) {
			category = "READ_TIMEOUT";
			outcome = "TIMEOUT";
		} else if (hasCause(exception, SSLException.class)) {
			category = "SSL_FAILED";
		} else if (normalized.contains("connection reset") || normalized.contains("forcibly closed")
				|| hasCause(exception, SocketException.class) && normalized.contains("reset")) {
			category = "CONNECTION_RESET";
		} else if (normalized.contains("proxy")) {
			category = "PROXY_FAILED";
		} else if (hasCause(exception, ConnectException.class)) {
			category = "CONNECTION_FAILED";
		} else if (hasCause(exception, IOException.class)) {
			category = "IO_FAILED";
		} else {
			category = "UNEXPECTED_ERROR";
		}

		return new Failure(outcome, provider + "_" + category, category,
				root.getClass().getName(), message);
	}

	static String safeMessage(Throwable throwable) {
		String value = throwable == null || throwable.getMessage() == null
				? "No message supplied" : throwable.getMessage();
		value = value.replaceAll("(?i)(authorization|x-api-key|x-rapidapi-key)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]")
				.replaceAll("(?i)([?&](?:api_key|key)=)[^&\\s]+", "$1[REDACTED]")
				.replaceAll("(?i)bearer\\s+[^\\s,;]+", "Bearer [REDACTED]")
				.replaceAll("[\\r\\n\\t]+", " ")
				.trim();
		return value.length() <= MAX_SAFE_MESSAGE_LENGTH
				? value : value.substring(0, MAX_SAFE_MESSAGE_LENGTH) + "…";
	}

	private static Throwable deepestCause(Throwable throwable) {
		Throwable current = throwable;
		while (current != null && current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current == null ? throwable : current;
	}

	private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
		Throwable current = throwable;
		while (current != null) {
			if (type.isInstance(current)) return true;
			if (current.getCause() == current) break;
			current = current.getCause();
		}
		return false;
	}

	record Failure(String outcome, String errorCode, String category,
			String rootCauseType, String safeMessage) {
	}
}
