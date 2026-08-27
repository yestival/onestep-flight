package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.DocumentType;
import com.selene.onestepflight.dto.DocumentFlightSegment;

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

	@Test
	@SuppressWarnings("unchecked")
	void imageSchemaHasItsOwnOrderedFieldsDatePrecisionAndShortRootDescription() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		NutrientBoardingPassDocumentProcessor processor =
				new NutrientBoardingPassDocumentProcessor(mapper);

		Map<String, Object> schema = processor.imageDocumentSchema();
		Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
		Map<String, Object> segmentArray = (Map<String, Object>) properties.get("segments");
		Map<String, Object> items = (Map<String, Object>) segmentArray.get("items");
		Map<String, Object> segmentProperties = (Map<String, Object>) items.get("properties");
		Map<String, Object> precision = (Map<String, Object>) segmentProperties
				.get("departure_date_precision");

		assertThat(properties.keySet()).containsExactly("document_type", "segments");
		assertThat(segmentProperties.keySet()).containsExactly(
				"flight_number", "departure_date", "departure_month_day",
				"departure_date_precision", "departure_airport", "arrival_airport",
				"scheduled_departure", "scheduled_arrival", "boarding_time", "terminal", "gate", "seat");
		assertThat(precision.get("enum")).isEqualTo(List.of(
				"FULL_DATE_VISIBLE", "MONTH_DAY_ONLY", "UNKNOWN"));
		assertThat(schema.get("required")).isEqualTo(List.of("document_type", "segments"));
		assertThat(schema.get("description")).isEqualTo(
				"Extract visible flight legs in top-to-bottom order. Bind each flight number, route, date, and time to its own flight block. A clearly scoped itinerary-header date may apply to following legs until a new date or journey section. Never copy fields between legs or infer a year. If only month and day are visible, omit departure_date and use departure_month_day. Omit uncertain values and sensitive passenger or booking data.");
		String json = mapper.writeValueAsString(schema);
		assertThat(json).doesNotContain("minimum", "additionalProperties");
	}

	@Test
	void everyPdfAndImageSchemaDescriptionFitsNutrientLimit() {
		NutrientBoardingPassDocumentProcessor processor =
				new NutrientBoardingPassDocumentProcessor(new ObjectMapper());
		List<String> descriptions = new ArrayList<>();

		collectDescriptions(processor.documentSchema(), descriptions);
		collectDescriptions(processor.imageDocumentSchema(), descriptions);

		assertThat(descriptions).isNotEmpty().allSatisfy(description ->
				assertThat(description.length()).isLessThanOrEqualTo(1024));
		int longestDescription = descriptions.stream().mapToInt(String::length).max().orElseThrow();
		assertThat(longestDescription)
				.as("all schema descriptions should stay comfortably below Nutrient's limit")
				.isLessThanOrEqualTo(512);
	}

	@Test
	void imageMonthDayPrecisionDropsAProviderInventedYearAndCompleteDateTimes() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		NutrientBoardingPassDocumentProcessor processor =
				new NutrientBoardingPassDocumentProcessor(mapper);
		String providerResponse = """
				{"output":{"data":{"document_type":"ITINERARY","segments":[{
				"flight_number":"KL1036","departure_date":"2024-08-04",
				"departure_month_day":"08-04","departure_date_precision":"MONTH_DAY_ONLY",
				"scheduled_departure":"2024-08-04T17:30","scheduled_arrival":"2024-08-04T19:10"}]},
				"metadata":{"document_type":{"confidence":0.99},"segments":[{
				"flight_number":{"confidence":0.99},"departure_date":{"confidence":0.99},
				"departure_month_day":{"confidence":0.99},"departure_date_precision":{"confidence":0.99},
				"scheduled_departure":{"confidence":0.99},"scheduled_arrival":{"confidence":0.99}}]}}}
				""";

		var response = processor.toImageResponse(mapper.readTree(providerResponse));
		DocumentFlightSegment segment = response.segments().get(0);

		assertThat(segment.flightNumber()).isEqualTo("KL1036");
		assertThat(segment.departureDate()).isNull();
		assertThat(segment.departureMonthDay()).isEqualTo("08-04");
		assertThat(segment.dateSource()).isEqualTo(DocumentFlightSegment.DateSource.MISSING);
		assertThat(segment.scheduledDeparture()).isNull();
		assertThat(segment.scheduledArrival()).isNull();
		assertThat(segment.fields().get("departure_date").value()).isNull();
		assertThat(segment.fields().get("departure_month_day").value()).isEqualTo("08-04");
		assertThat(DocumentResultClassifier.classify(response))
				.isEqualTo(DocumentResultClassification.PARTIAL);
	}

	@Test
	void imageFullDateIsKeptOnlyWithFullDateVisiblePrecision() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		NutrientBoardingPassDocumentProcessor processor =
				new NutrientBoardingPassDocumentProcessor(mapper);
		String providerResponse = """
				{"output":{"data":{"document_type":"BOARDING_PASS","segments":[{
				"flight_number":"MF812","departure_date":"2025-07-22",
				"departure_month_day":"07-22","departure_date_precision":"FULL_DATE_VISIBLE",
				"scheduled_departure":"2025-07-22T21:40"}]},
				"metadata":{"document_type":{"confidence":0.99},"segments":[{
				"flight_number":{"confidence":0.99},"departure_date":{"confidence":0.99},
				"departure_month_day":{"confidence":0.99},"departure_date_precision":{"confidence":0.99},
				"scheduled_departure":{"confidence":0.99}}]}}}
				""";

		var response = processor.toImageResponse(mapper.readTree(providerResponse));
		DocumentFlightSegment segment = response.segments().get(0);

		assertThat(segment.departureDate()).hasToString("2025-07-22");
		assertThat(segment.departureMonthDay()).isEqualTo("07-22");
		assertThat(segment.dateSource()).isEqualTo(DocumentFlightSegment.DateSource.EXPLICIT_FULL_DATE);
		assertThat(segment.scheduledDeparture()).isEqualTo("2025-07-22T21:40");
		assertThat(DocumentResultClassifier.classify(response))
				.isEqualTo(DocumentResultClassification.USABLE);
	}

	@Test
	void duplicateImageFlightNumbersKeepBothSegmentsInOrderAndRequireConfirmation() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		NutrientBoardingPassDocumentProcessor processor =
				new NutrientBoardingPassDocumentProcessor(mapper);
		String providerResponse = imageSegmentsResponse("""
				[{"flight_number":"KL1036","departure_date":"2025-07-22","departure_date_precision":"FULL_DATE_VISIBLE","departure_airport":"AMS","arrival_airport":"MAD"},
				 {"flight_number":"KL1036","departure_date":"2025-07-23","departure_date_precision":"FULL_DATE_VISIBLE","departure_airport":"MAD","arrival_airport":"AMS"}]
				""", """
				[{"flight_number":{"confidence":0.99},"departure_date":{"confidence":0.99}},
				 {"flight_number":{"confidence":0.98},"departure_date":{"confidence":0.98}}]
				""");

		var response = processor.toImageResponse(mapper.readTree(providerResponse));

		assertThat(response.segments()).extracting(DocumentFlightSegment::flightNumber)
				.containsExactly("KL1036", "KL1036");
		assertThat(response.segments()).extracting(DocumentFlightSegment::departureAirport)
				.containsExactly("AMS", "MAD");
		assertThat(response.segments()).allMatch(segment -> segment.requiresFlightNumberConfirmation()
				&& !segment.flightNumberConfirmedByUser());
		assertThat(response.needsReviewFields()).contains(
				"segments[0].flight_number", "segments[1].flight_number");
		assertThat(DocumentResultClassifier.classify(response))
				.isEqualTo(DocumentResultClassification.PARTIAL);
	}

	@Test
	void distinctImageFlightNumbersAndTheirFieldsStayInOriginalSegments() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		NutrientBoardingPassDocumentProcessor processor =
				new NutrientBoardingPassDocumentProcessor(mapper);
		String providerResponse = imageSegmentsResponse("""
				[{"flight_number":"KL1036","departure_date":"2025-07-22","departure_date_precision":"FULL_DATE_VISIBLE","departure_airport":"AMS","arrival_airport":"MAD"},
				 {"flight_number":"MF812","departure_date":"2025-07-23","departure_date_precision":"FULL_DATE_VISIBLE","departure_airport":"XMN","arrival_airport":"AMS"}]
				""", """
				[{"flight_number":{"confidence":0.99},"departure_date":{"confidence":0.99}},
				 {"flight_number":{"confidence":0.98},"departure_date":{"confidence":0.98}}]
				""");

		var response = processor.toImageResponse(mapper.readTree(providerResponse));

		assertThat(response.segments()).extracting(
				DocumentFlightSegment::flightNumber,
				DocumentFlightSegment::departureAirport,
				DocumentFlightSegment::arrivalAirport)
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple("KL1036", "AMS", "MAD"),
						org.assertj.core.groups.Tuple.tuple("MF812", "XMN", "AMS"));
		assertThat(response.segments()).noneMatch(DocumentFlightSegment::requiresFlightNumberConfirmation);
	}

	@Test
	void zeroOrMissingImageFlightEvidenceConfidenceIsLowAndBlocksLookup() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		NutrientBoardingPassDocumentProcessor processor =
				new NutrientBoardingPassDocumentProcessor(mapper);
		String providerResponse = imageSegmentsResponse("""
				[{"flight_number":"KL1036","departure_date":"2025-07-22","departure_date_precision":"FULL_DATE_VISIBLE"},
				 {"flight_number":"MF812","departure_date":"2025-07-23","departure_date_precision":"FULL_DATE_VISIBLE"}]
				""", """
				[{"flight_number":{"confidence":0},"departure_date":{"confidence":0.99}},
				 {"departure_date":{"confidence":0.99}}]
				""");

		var response = processor.toImageResponse(mapper.readTree(providerResponse));

		assertThat(response.segments()).extracting(DocumentFlightSegment::flightNumberConfidence)
				.containsExactly(DocumentFlightSegment.FlightNumberConfidence.LOW,
						DocumentFlightSegment.FlightNumberConfidence.LOW);
		assertThat(response.segments()).allMatch(DocumentFlightSegment::requiresFlightNumberConfirmation);
		assertThat(DocumentResultClassifier.classify(response))
				.isEqualTo(DocumentResultClassification.PARTIAL);
	}

	private String imageSegmentsResponse(String segments, String metadata) {
		return """
				{"output":{"data":{"document_type":"ITINERARY","segments":%s},
				"metadata":{"document_type":{"confidence":0.99},"segments":%s}}}
				""".formatted(segments, metadata);
	}

	private void collectDescriptions(Object value, List<String> descriptions) {
		if (value instanceof Map<?, ?> map) {
			map.forEach((key, nestedValue) -> {
				if ("description".equals(key) && nestedValue instanceof String description) {
					descriptions.add(description);
				}
				collectDescriptions(nestedValue, descriptions);
			});
		}
		else if (value instanceof Iterable<?> iterable) {
			iterable.forEach(item -> collectDescriptions(item, descriptions));
		}
	}
}
