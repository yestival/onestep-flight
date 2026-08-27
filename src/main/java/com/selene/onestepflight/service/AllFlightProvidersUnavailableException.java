package com.selene.onestepflight.service;

import java.util.Map;

import com.selene.onestepflight.dto.ProviderFlightResult;

public class AllFlightProvidersUnavailableException extends RuntimeException {

	private final Map<String, ProviderFlightResult> providerResults;
	private final String code;

	public AllFlightProvidersUnavailableException(Map<String, ProviderFlightResult> providerResults) {
		this(classify(providerResults), providerResults);
	}

	private AllFlightProvidersUnavailableException(String code,
			Map<String, ProviderFlightResult> providerResults) {
		super(code);
		this.code = code;
		this.providerResults = Map.copyOf(providerResults);
	}

	public String code() {
		return code;
	}

	public Map<String, ProviderFlightResult> providerResults() {
		return providerResults;
	}

	private static String classify(Map<String, ProviderFlightResult> providerResults) {
		var attempted = providerResults.values().stream()
				.filter(result -> result != null
						&& !ProviderFlightResult.NOT_CONFIGURED.equals(result.outcome()))
				.toList();
		if (!attempted.isEmpty() && attempted.stream().allMatch(result ->
				ProviderFlightResult.NOT_FOUND.equals(result.outcome())
						|| ProviderFlightResult.IDENTITY_MISMATCH.equals(result.outcome()))) {
			return "FLIGHT_NOT_FOUND";
		}
		if (!attempted.isEmpty() && attempted.stream().allMatch(result ->
				ProviderFlightResult.TIMEOUT.equals(result.outcome()))) return "LIVE_FLIGHT_TIMEOUT";
		if (!attempted.isEmpty() && attempted.stream().allMatch(result ->
				ProviderFlightResult.AUTH_ERROR.equals(result.outcome()))) return "LIVE_FLIGHT_AUTH_ERROR";
		if (!attempted.isEmpty() && attempted.stream().allMatch(result ->
				ProviderFlightResult.RATE_LIMITED.equals(result.outcome()))) return "LIVE_FLIGHT_RATE_LIMITED";
		return "ALL_LIVE_FLIGHT_PROVIDERS_FAILED";
	}
}
