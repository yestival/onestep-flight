package com.selene.onestepflight.service;

import java.util.Map;

import com.selene.onestepflight.dto.ProviderFlightResult;

public class AllFlightProvidersUnavailableException extends RuntimeException {

	private final Map<String, ProviderFlightResult> providerResults;

	public AllFlightProvidersUnavailableException(Map<String, ProviderFlightResult> providerResults) {
		super("ALL_LIVE_FLIGHT_PROVIDERS_FAILED");
		this.providerResults = Map.copyOf(providerResults);
	}

	public Map<String, ProviderFlightResult> providerResults() {
		return providerResults;
	}
}
