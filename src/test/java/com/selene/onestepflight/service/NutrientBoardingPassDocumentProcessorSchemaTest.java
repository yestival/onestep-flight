package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tools.jackson.databind.ObjectMapper;

class NutrientBoardingPassDocumentProcessorSchemaTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final NutrientBoardingPassDocumentProcessor processor =
			new NutrientBoardingPassDocumentProcessor(mapper,
					Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));

	@Test
	void understandAndStructureInstructionsAreFixedAndMimeAware() {
		Map<String, Object> png = processor.understandInstructions("image/png");
		Map<String, Object> jpeg = processor.understandInstructions("IMAGE/JPEG");
		Map<String, Object> pdf = processor.understandInstructions("application/pdf");

		assertThat(png).containsOnlyKeys(
				"mode", "schema", "includeCitations", "strict", "multimodal");
		assertThat(png).containsEntry("mode", "understand")
				.containsEntry("includeCitations", true)
				.containsEntry("strict", false)
				.containsEntry("multimodal", true);
		assertThat(jpeg).containsEntry("multimodal", true);
		assertThat(pdf).containsEntry("multimodal", false);
		assertThat(processor.structureInstructions()).isEqualTo(Map.of(
				"mode", "structure", "output", Map.of("format", "spatial")));
	}

	@Test
	@SuppressWarnings("unchecked")
	void schemaUsesOnlySupportedKeywordsAndRequiresOrderedPerFlightSegments() throws Exception {
		Map<String, Object> schema = processor.documentSchema();
		Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
		Map<String, Object> segments = (Map<String, Object>) properties.get("segments");
		Map<String, Object> item = (Map<String, Object>) segments.get("items");
		Map<String, Object> segmentFields = (Map<String, Object>) item.get("properties");
		Map<String, Object> departureDate = (Map<String, Object>) segmentFields.get("departure_date");

		assertThat(schema).containsEntry("type", "object");
		assertThat(schema.get("required")).isEqualTo(List.of("document_type", "segments"));
		assertThat(properties).containsOnlyKeys("document_type", "segments");
		assertThat(segmentFields).containsKeys(
				"flight_number", "departure_airport", "arrival_airport", "departure_date",
				"scheduled_departure", "scheduled_arrival", "departure_terminal", "arrival_terminal");
		assertThat(segmentFields).doesNotContainKeys("document_order", "passenger_name", "ticket_number",
				"passport", "pnr", "booking_reference", "barcode");
		assertThat(departureDate).containsEntry("type", "string").doesNotContainKey("format");
		assertThat(segments.get("description").toString()).contains(
				"one segment for every distinct marketed flight number",
				"Preserve the visual order",
				"Bind each flight number only",
				"Do not replace an individual segment route",
				"Do not invent a year",
				"Keep partially extracted segments");

		String json = mapper.writeValueAsString(schema);
		assertThat(json).doesNotContain(
				"\"minimum\"", "\"maximum\"", "\"oneOf\"", "\"anyOf\"",
				"\"allOf\"", "\"$ref\"", "\"if\"", "\"then\"", "\"else\"");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"8月4日|08-04",
			"9月5日|09-05",
			"8/4|08-04",
			"08/04|08-04",
			"8-4|08-04",
			"Aug 4|08-04",
			"aUg 4|08-04",
			"4 Aug|08-04",
			"4 agosto|08-04"
	})
	void yearlessDatesUseTheInjectedCurrentYear(String visible, String expectedMonthDay) {
		var parsed = processor.dateParts(visible);

		assertThat(parsed.date()).isEqualTo(LocalDate.parse("2026-" + expectedMonthDay));
		assertThat(parsed.monthDay()).isEqualTo(expectedMonthDay);
		assertThat(parsed.publicValue()).isEqualTo("2026-" + expectedMonthDay);
		assertThat(parsed.source()).isEqualTo(
				com.selene.onestepflight.dto.DocumentFlightSegment.DateSource.CURRENT_YEAR_FROM_MONTH_DAY);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"2026-08-04|2026-08-04|08-04",
			"2025-08-04|2025-08-04|08-04",
			"2026年8月4日|2026-08-04|08-04",
			"2026/8/4|2026-08-04|08-04",
			"Aug 4, 2026|2026-08-04|08-04",
			"4 Aug 2026|2026-08-04|08-04"
	})
	void completeDatesProduceBothLocalDateAndMonthDay(String visible, String expectedDate,
			String expectedMonthDay) {
		var parsed = processor.dateParts(visible);

		assertThat(parsed.date()).isEqualTo(LocalDate.parse(expectedDate));
		assertThat(parsed.monthDay()).isEqualTo(expectedMonthDay);
		assertThat(parsed.publicValue()).isEqualTo(expectedDate);
		assertThat(parsed.source()).isEqualTo(
				com.selene.onestepflight.dto.DocumentFlightSegment.DateSource.EXPLICIT_FULL_DATE);
	}

	@Test
	void invalidCalendarDatesAreDiscarded() {
		assertThat(processor.dateParts("2/30").hasValue()).isFalse();
		assertThat(processor.dateParts("2/29").hasValue()).isFalse();
		assertThat(processor.dateParts("2025-02-30").hasValue()).isFalse();
		assertThat(processor.dateParts("summer").hasValue()).isFalse();
	}

	@Test
	void currentYearCompletionUsesRealLeapYearRules() {
		NutrientBoardingPassDocumentProcessor leapYearProcessor =
				new NutrientBoardingPassDocumentProcessor(mapper,
						Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC));

		assertThat(leapYearProcessor.dateParts("2月29日").date())
				.isEqualTo(LocalDate.of(2024, 2, 29));
		assertThat(leapYearProcessor.dateParts("2月29日").source()).isEqualTo(
				com.selene.onestepflight.dto.DocumentFlightSegment.DateSource.CURRENT_YEAR_FROM_MONTH_DAY);
		assertThat(processor.dateParts("2024-02-29").date()).isEqualTo(LocalDate.of(2024, 2, 29));
	}

	@Test
	void missingDateRemainsMissing() {
		var parsed = processor.dateParts(null);

		assertThat(parsed.hasValue()).isFalse();
		assertThat(parsed.source()).isEqualTo(
				com.selene.onestepflight.dto.DocumentFlightSegment.DateSource.MISSING);
	}

	@Test
	void scheduledIsoDepartureDerivesBothDateRepresentations() throws Exception {
		String response = """
				{"output":{"data":{"document_type":"ITINERARY","segments":[{
				"flight_number":"BA117","departure_airport":"LHR","arrival_airport":"JFK",
				"scheduled_departure":"2026-08-04T17:30"}]},
				"metadata":{"document_type":{"confidence":0.99},"segments":[{
				"flight_number":{"confidence":0.99},"scheduled_departure":{"confidence":0.99}}]}}}
				""";

		var result = processor.toResponse(mapper.readTree(response));

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.departureDate()).isEqualTo(LocalDate.of(2026, 8, 4));
			assertThat(segment.departureMonthDay()).isEqualTo("08-04");
			assertThat(segment.scheduledDeparture()).isEqualTo("2026-08-04T17:30");
		});
		assertThat(result.departureDate()).isEqualTo(LocalDate.of(2026, 8, 4));
		assertThat(result.departureMonthDay()).isEqualTo("08-04");
	}

	@Test
	void monthDayOnlyUnderstandResultIsNormalizedAtThePublicBoundary() throws Exception {
		String response = """
				{"output":{"data":{"document_type":"ITINERARY","segments":[{
				"flight_number":"MF825","departure_date":"9月5日",
				"departure_airport":"XMN","arrival_airport":"CDG"}]},
				"metadata":{"document_type":{"confidence":0.99}}}}
				""";

		var result = processor.toResponse(mapper.readTree(response));
		String publicJson = mapper.writeValueAsString(result);

		assertThat(result.departureDate()).isEqualTo(LocalDate.of(2026, 9, 5));
		assertThat(result.departureMonthDay()).isEqualTo("09-05");
		assertThat(result.dateSource()).isEqualTo(
				com.selene.onestepflight.dto.DocumentFlightSegment.DateSource.CURRENT_YEAR_FROM_MONTH_DAY);
		assertThat(result.fields().get("departure_date").value()).isEqualTo("2026-09-05");
		assertThat(result.fields().get("departure_date").rawValue()).isEqualTo("9月5日");
		assertThat(publicJson).contains("\"departureDate\":\"2026-09-05\"",
				"\"departureMonthDay\":\"09-05\"",
				"\"dateSource\":\"CURRENT_YEAR_FROM_MONTH_DAY\"")
				.doesNotContain("9月5日", "rawValue");
	}

	@Test
	void flightNumberNormalizationRejectsCommonDocumentNoise() {
		assertThat(processor.normalizeFlightNumber("KL 1036")).isEqualTo("KL1036");
		assertThat(processor.normalizeFlightNumber("AF1668A")).isEqualTo("AF1668A");
		assertThat(processor.normalizeFlightNumber("17:30")).isNull();
		assertThat(processor.normalizeFlightNumber("8月4日")).isNull();
		assertThat(processor.normalizeFlightNumber("T2")).isNull();
		assertThat(processor.normalizeFlightNumber("23KG")).isNull();
		assertThat(processor.normalizeFlightNumber("9999999999999")).isNull();
	}
}
