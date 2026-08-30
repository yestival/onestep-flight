package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.ProviderFlightResult;

class RealIntegrationCallStatusTest {

	@Test
	void simulatedScenarioCallsNeverOverwriteTheLastRealProviderObservations() {
		RealIntegrationCallStatus status = new RealIntegrationCallStatus();
		Instant nutrientAt = Instant.parse("2026-08-31T01:00:00Z");
		Instant serpAt = Instant.parse("2026-08-31T01:01:00Z");
		status.recordNutrient(true, 2, nutrientAt);
		status.recordSerpApi(ProviderFlightResult.failure("SERPAPI", ProviderFlightResult.TIMEOUT,
				"SERPAPI_TIMEOUT", serpAt), serpAt);
		RealIntegrationCallStatus.Snapshot before = status.snapshot();

		DemoScenarioFlightDataProvider demo = new DemoScenarioFlightDataProvider();
		demo.findInitialSnapshot("b9500530-069d-4f82-8ed9-0dbfe6b65e9f", "BA117", "GATE_CHANGE");
		demo.findSnapshot("b9500530-069d-4f82-8ed9-0dbfe6b65e9f", "BA117", "GATE_CHANGE", true);

		assertThat(status.snapshot()).isEqualTo(before);
		assertThat(status.snapshot().nutrient().segmentCount()).isEqualTo(2);
		assertThat(status.snapshot().serpApi().outcome()).isEqualTo(ProviderFlightResult.TIMEOUT);
		assertThat(status.snapshot().serpApi().live()).isFalse();
	}
}
