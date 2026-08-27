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
		Map<String, Object> segmentArray = (Map<String, Object>) properties.get("segments");
		Map<String, Object> segmentItems = (Map<String, Object>) segmentArray.get("items");
		Map<String, Object> segmentProperties = (Map<String, Object>) segmentItems.get("properties");

		assertThat(schema.get("required")).isEqualTo(List.of("document_type", "segments"));
		assertThat(typeProperty.get("enum")).isEqualTo(List.of(
				"BOARDING_PASS", "ITINERARY", "E_TICKET", "BOOKING_CONFIRMATION", "UNKNOWN"));
		assertThat(properties).containsOnlyKeys("document_type", "segments");
		assertThat(segmentProperties).containsKeys("flight_number", "departure_date", "departure_airport",
				"arrival_airport", "scheduled_departure", "scheduled_arrival", "boarding_time",
				"terminal", "gate", "seat");
		assertThat(segmentProperties).doesNotContainKeys(
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
		assertThat(response.segments()).hasSize(1);
		assertThat(response.flightNumber()).isEqualTo("BA117");
	}

	@Test
	void multiSegmentItineraryIsReturnedAsChoicesWithoutSelectingTheFirstLeg() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		NutrientBoardingPassDocumentProcessor processor =
				new NutrientBoardingPassDocumentProcessor(mapper);
		String providerResponse = """
				{"output":{"data":{"document_type":"ITINERARY","segments":[
				{"flight_number":"BA117","departure_date":"2026-08-24","departure_airport":"LHR","arrival_airport":"JFK","scheduled_departure":"2026-08-24T08:20","scheduled_arrival":"2026-08-24T11:10"},
				{"flight_number":"BA178","departure_date":"2026-09-02","departure_airport":"JFK","arrival_airport":"LHR","scheduled_departure":"2026-09-02T08:00","scheduled_arrival":"2026-09-02T20:00"}
				]},"metadata":{"document_type":{"confidence":0.99},"segments":[
				{"flight_number":{"confidence":0.99,"pageIndex":0},"departure_date":{"confidence":0.99},"departure_airport":{"confidence":0.99},"arrival_airport":{"confidence":0.99}},
				{"flight_number":{"confidence":0.98,"pageIndex":1},"departure_date":{"confidence":0.98},"departure_airport":{"confidence":0.98},"arrival_airport":{"confidence":0.98}}
				]}}}
				""";

		var response = processor.toResponse(mapper.readTree(providerResponse));

		assertThat(response.documentType()).isEqualTo(DocumentType.ITINERARY);
		assertThat(response.segments()).extracting(segment -> segment.flightNumber())
				.containsExactly("BA117", "BA178");
		assertThat(response.segments().get(0).scheduledArrival()).isEqualTo("2026-08-24T11:10");
		assertThat(response.flightNumber()).as("multi-leg documents require an explicit traveler choice").isNull();
		assertThat(response.departureDate()).isNull();
		assertThat(response.requiresDocumentConfirmation()).isFalse();
	}

	@Test
	void invalidIdentityValuesAreRemovedAndSingleSegmentReviewKeysAreFlat() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		NutrientBoardingPassDocumentProcessor processor =
				new NutrientBoardingPassDocumentProcessor(mapper);
		String providerResponse = """
				{"output":{"data":{"document_type":"BOARDING_PASS","segments":[{
				"flight_number":"PRIVATEPNR","departure_date":"not-a-date",
				"departure_airport":"Heathrow","arrival_airport":"JFK"}]},
				"metadata":{"document_type":{"confidence":0.99},"segments":[{
				"flight_number":{"confidence":0.99},"departure_date":{"confidence":0.99},
				"departure_airport":{"confidence":0.99},"arrival_airport":{"confidence":0.99}}]}}}
				""";

		var response = processor.toResponse(mapper.readTree(providerResponse));

		assertThat(response.flightNumber()).isNull();
		assertThat(response.departureDate()).isNull();
		assertThat(response.departureAirport()).isNull();
		assertThat(response.arrivalAirport()).isEqualTo("JFK");
		assertThat(response.boardingPassDetected()).isFalse();
		assertThat(response.needsReviewFields()).contains("flight_number", "departure_date", "departure_airport");
		assertThat(response.needsReviewFields()).allMatch(name -> !name.startsWith("segments[0]."));
		assertThat(response.needsReviewFields()).doesNotContain("terminal", "gate", "seat", "boarding_time");
	}
}
