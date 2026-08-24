package com.selene.onestepflight.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations")
public class IntegrationStatusController {

	@GetMapping("/status")
	public Map<String, String> status() {
		Map<String, String> result = new LinkedHashMap<>();
		result.put("nutrient", readiness("NUTRIENT_API_KEY"));
		result.put("serpApi", readiness("SERPAPI_API_KEY"));
		result.put("aeroDataBox", readiness("AERODATABOX_RAPIDAPI_KEY"));
		result.put("variFlight", readiness("VARIFLIGHT_API_KEY"));
		return result;
	}

	private String readiness(String environmentVariable) {
		String value = System.getenv(environmentVariable);
		return value == null || value.isBlank() ? "MISSING" : "READY";
	}
}
