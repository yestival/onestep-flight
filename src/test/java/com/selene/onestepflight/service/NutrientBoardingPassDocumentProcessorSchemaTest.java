package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

class NutrientBoardingPassDocumentProcessorSchemaTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final NutrientBoardingPassDocumentProcessor processor =
			new NutrientBoardingPassDocumentProcessor(mapper,
					Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));

	@Test
	void nutrientClientNeverFollowsRedirectsThatCouldCreateAThirdRequest() {
		assertThat(NutrientBoardingPassDocumentProcessor.redirectPolicy())
				.isEqualTo(HttpClient.Redirect.NEVER);
	}

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
	void nativeTextPdfUsesTextThenStructureWithTheSameOriginalBytes() throws Exception {
		byte[] pdf = nativeTextPdf();
		PdfInputClassifier.Analysis analysis = new PdfInputClassifier().analyze(pdf);
		assertThat(analysis.type()).isEqualTo(PdfInputClassifier.Type.NATIVE_TEXT);
		assertThat(analysis.pageTexts()).singleElement().asString().contains("Flight BA117");
		assertPdfPath(pdf, "text");
	}

	@Test
	void imageStylePdfUsesUnderstandThenStructureWithTheSameOriginalBytes() throws Exception {
		assertPdfPath(blankPdf(), "understand");
	}

	private void assertPdfPath(byte[] original, String expectedFirstMode) {
		List<byte[]> receivedFiles = java.util.Collections.synchronizedList(new ArrayList<>());
		List<String> modes = java.util.Collections.synchronizedList(new ArrayList<>());
		NutrientBoardingPassDocumentProcessor pathProcessor =
				new NutrientBoardingPassDocumentProcessor(mapper,
						Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC),
						(bytes, filename, contentType, stage, instructions) -> {
							receivedFiles.add(bytes);
							modes.add(String.valueOf(instructions.get("mode")));
							throw new ExternalServiceUnavailableException("TEST_NO_EXTERNAL_REQUEST");
						});

		assertThatThrownBy(() -> pathProcessor.extract(original, "flight.pdf", "application/pdf"))
				.isInstanceOf(ExternalServiceUnavailableException.class);
		assertThat(modes).containsExactlyInAnyOrder(expectedFirstMode, "structure");
		assertThat(receivedFiles).hasSize(2).allSatisfy(bytes -> assertThat(bytes).isSameAs(original));
	}

	private byte[] nativeTextPdf() throws Exception {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			PDPage page = new PDPage();
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				content.newLineAtOffset(50, 700);
				content.showText("Flight BA117 departs London Heathrow for New York on 25 August 2026");
				content.endText();
			}
			document.save(output);
			return output.toByteArray();
		}
	}

	private byte[] blankPdf() throws Exception {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			document.addPage(new PDPage());
			document.save(output);
			return output.toByteArray();
		}
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
		Map<String, Object> flightNumber = (Map<String, Object>) segmentFields.get("flight_number");
		Map<String, Object> departureAirport = (Map<String, Object>) segmentFields.get("departure_airport");

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
				"Keep every genuinely distinct flight segment",
				"Preserve the visual order",
				"Bind each flight number only",
				"Do not replace an individual segment route",
				"Do not invent a year",
				"Keep a genuinely distinct incomplete flight segment",
				"Do not create extra segments from airport/time/date fragments",
				"Do not create separate segments from date/time fragments",
				"Do not create a route from contact-information headers");
		assertThat(flightNumber.get("description").toString()).contains(
				"complete marketed flight designator exactly as printed", "Aircraft models are not flight numbers",
				"Do not use PNRs", "currency amounts");
		assertThat(departureDate.get("description").toString()).contains(
				"scheduled departure date", "actual arrival date", "issue date");
		assertThat(((Map<String, Object>) segmentFields.get("scheduled_departure"))
				.get("description").toString()).contains("preserve the printed minutes", "Do not round");
		assertThat(departureAirport.get("description").toString()).contains(
				"origin location belonging to the flight route", "Do not use currency codes",
				"telephone, fax, email, website, contact");

		String json = mapper.writeValueAsString(schema);
		assertThat(json).doesNotContain(
				"\"minimum\"", "\"maximum\"", "\"oneOf\"", "\"anyOf\"",
				"\"allOf\"", "\"$ref\"", "\"if\"", "\"then\"", "\"else\"");
	}

	@Test
	void structureTokensKeepOwnAndContainerBoundsSeparate() throws Exception {
		JsonNode response = mapper.readTree("""
				{"output":{"elements":[{"type":"card","pageIndex":0,"bounds":[10,20,400,220],"children":[
				 {"type":"token","text":"XY123"},
				 {"type":"token","text":"AAA","bounds":[30,80,40,20]},
				 {"type":"date","text":"出发 2026-08-04","bounds":[30,120,130,20]}
				]}]}}
				""");
		NutrientBoardingPassDocumentProcessor.SpatialPass spatial = processor.parseSpatial(response);
		NutrientBoardingPassDocumentProcessor.EvidenceBlock flight = spatial.blocks().stream()
				.filter(block -> "XY123".equals(block.text())).findFirst().orElseThrow();
		assertThat(flight.hasOwnBoundingBox()).isFalse();
		assertThat(flight.ownBoundingBox()).isNull();
		assertThat(flight.containerBoundingBox()).isNotNull();
		assertThat(flight.regionPath()).isNotEqualTo("$");
		assertThat(flight.source()).isEqualTo("STRUCTURE");
		assertThat(flight.sourceBundleId()).isNotBlank();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"8月4日|08-04",
			"9月5日|09-05",
			"13/8|08-13",
			"8/13|08-13",
			"Aug 4|08-04",
			"aUg 4|08-04",
			"4 Aug|08-04",
			"4 agosto|08-04",
			"4AUG|08-04"
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
	void structuredDateCandidatesPreserveCompactAndAmbiguousResults() {
		assertThat(processor.dateParts("04AUG26").candidates())
				.containsExactly(LocalDate.of(2026, 8, 4));
		assertThat(processor.dateParts("4 de agosto de 2026").candidates())
				.containsExactly(LocalDate.of(2026, 8, 4));
		assertThat(processor.dateParts("04/08/2026").candidates())
				.containsExactly(LocalDate.of(2026, 4, 8), LocalDate.of(2026, 8, 4));
		assertThat(processor.dateParts("04/08/2026").source()).isEqualTo(
				com.selene.onestepflight.dto.DocumentFlightSegment.DateSource.CONFLICT);
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
		assertThat(processor.normalizeFlightNumber("BA - 269")).isEqualTo("BA269");
		assertThat(processor.normalizeFlightNumber("QF-12")).isEqualTo("QF12");
		assertThat(processor.normalizeFlightNumber("G9 123")).isEqualTo("G9123");
		assertThat(processor.normalizeFlightNumber("AF1668A")).isEqualTo("AF1668A");
		assertThat(processor.normalizeFlightNumber("17:30")).isNull();
		assertThat(processor.normalizeFlightNumber("8月4日")).isNull();
		assertThat(processor.normalizeFlightNumber("T2")).isNull();
		assertThat(processor.normalizeFlightNumber("23KG")).isNull();
		assertThat(processor.normalizeFlightNumber("9999999999999")).isNull();
	}
}
