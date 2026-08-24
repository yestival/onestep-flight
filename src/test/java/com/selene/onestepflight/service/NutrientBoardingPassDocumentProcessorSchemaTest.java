package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.DocumentType;

import tools.jackson.databind.ObjectMapper;

class NutrientBoardingPassDocumentProcessorSchemaTest {

	@Test
	@SuppressWarnings("unchecked")
	void schemaAllowsUnknownWithoutInventingFlightIdentityAndExcludesSensitiveFields() {
		NutrientBoardingPassDocumentProcessor processor =
				new NutrientBoardingPassDocumentProcessor(new ObjectMapper());

		Map<String, Object> schema = processor.documentSchema();
		Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
		Map<String, Object> typeProperty = (Map<String, Object>) properties.get("document_type");

		assertThat(schema.get("required")).isEqualTo(List.of("document_type"));
		assertThat(typeProperty.get("enum")).isEqualTo(List.of(
				"BOARDING_PASS", "ITINERARY", "E_TICKET", "BOOKING_CONFIRMATION", "UNKNOWN"));
		assertThat(properties).doesNotContainKeys(
				"name", "passenger_name", "pnr", "booking_reference", "ticket_number", "barcode");
		// Nutrient currently rejects the otherwise-valid JSON Schema
		// additionalProperties keyword with HTTP 400. The public response DTO still
		// applies its own strict evidence-field whitelist.
		assertThat(schema).doesNotContainKey("additionalProperties");
	}

	@Test
	void providerLabelsAreNormalizedInBothTheSemanticTypeAndEvidence() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		NutrientBoardingPassDocumentProcessor processor =
				new NutrientBoardingPassDocumentProcessor(mapper);
		String providerResponse = """
				{"output":{"data":{"document_type":"TICKET","flight_number":"BA 117","departure_date":"2026-08-24"},
				"metadata":{"document_type":{"confidence":0.99},"flight_number":{"confidence":0.99},"departure_date":{"confidence":0.99}}}}
				""";

		var response = processor.toResponse(mapper.readTree(providerResponse));

		assertThat(response.documentType()).isEqualTo(DocumentType.E_TICKET);
		assertThat(response.fields().get("document_type").value()).isEqualTo("E_TICKET");
		assertThat(response.boardingPassDetected()).isFalse();
	}
}
