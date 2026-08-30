package com.selene.onestepflight.service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.selene.onestepflight.dto.ProviderFlightResult;

/** Keeps only the most recent real provider observation; demo providers never call this service. */
@Service
public class RealIntegrationCallStatus {

	private final AtomicReference<NutrientCall> nutrient = new AtomicReference<>();
	private final AtomicReference<SerpApiCall> serpApi = new AtomicReference<>();

	public void recordNutrient(boolean success, int segmentCount, Instant calledAt) {
		nutrient.set(new NutrientCall(success, Math.max(0, segmentCount), calledAt));
	}

	public void recordSerpApi(ProviderFlightResult result, Instant calledAt) {
		if (result == null || !"SERPAPI".equals(result.provider())) return;
		serpApi.set(new SerpApiCall(result.outcome(), result.live(), result.fromCache(), calledAt,
				result.terminal(), result.gate(), result.status()));
	}

	public Snapshot snapshot() {
		return new Snapshot(nutrient.get(), serpApi.get());
	}

	public record Snapshot(NutrientCall nutrient, SerpApiCall serpApi) { }

	public record NutrientCall(boolean success, int segmentCount, Instant calledAt) { }

	public record SerpApiCall(String outcome, boolean live, boolean fromCache, Instant calledAt,
			String terminal, String gate, String status) { }
}
