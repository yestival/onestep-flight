package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DocumentExtractionPipelineTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC);
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void fixedSyntheticSamplesNormalizeStrictlyAndCompleteFilesUseOneNutrientCall() throws Exception {
		assertFixedSample("fixed-mf825-af1668.json",
				List.of("MF825", "AF1668"),
				List.of("XMN-CDG", "CDG-MAN"),
				List.of(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5)),
				List.of("2026-09-05T00:10", "2026-09-05T09:25"));
		assertFixedSample("fixed-kl1036-mf812.json",
				List.of("KL1036", "MF812"),
				List.of("MAN-AMS", "AMS-XMN"),
				List.of(LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 4)),
				List.of("2026-08-04T17:30", "2026-08-04T21:40"));
	}

	@Test
	void degradedFirstPassRecoversMf812WithoutChangingTheExistingLegOrOrder() throws Exception {
		JsonNode first = documentResponse("""
				[{"flight_number":"KL1036","departure_date":"2026-08-04","departure_airport":"MAN","arrival_airport":"AMS","scheduled_departure":"2026-08-04T17:30"},
				 {"departure_airport":"AMS","arrival_airport":"XMN","scheduled_departure":"2026-08-04T21:40","scheduled_arrival":"2026-08-05T14:20"}]
				""", twoCandidates("KL1036", "MF812"), 2, """
				[{"flight_number":{"confidence":0.99},"departure_date":{"confidence":0.99},"scheduled_departure":{"confidence":0.99}},
				 {"scheduled_departure":{"confidence":0.99}}]
				""");
		JsonNode evidence = evidenceResponse(twoCandidates("KL1036", "MF812"), 2, """
				[{"document_order":0,"flight_number_raw":"KL1036","departure_airport_raw":"MAN","arrival_airport_raw":"AMS"},
				 {"document_order":1,"flight_number_raw":"MF 812","departure_airport_raw":"AMS","arrival_airport_raw":"XMN"}]
				""");
		JsonNode focused = focusedResponse("""
				[{"document_order":1,"flight_number":"MF 812"}]
				""", """
				[{"flight_number":{"confidence":0.97}}]
				""");
		RecordingRequester requester = new RecordingRequester(first, evidence, focused);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 1 }, "synthetic.png", "image/png");

		assertThat(requester.stages).containsExactly("first-pass", "raw-evidence", "focused-pass");
		assertThat(result.segments()).hasSize(2);
		assertThat(result.segments()).extracting(segment -> segment.flightNumber())
				.containsExactly("KL1036", "MF812");
		assertThat(result.segments()).extracting(segment -> segment.departureDate())
				.containsExactly(LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 4));
		assertThat(result.segments()).extracting(segment ->
				segment.departureAirport() + "-" + segment.arrivalAirport())
				.containsExactly("MAN-AMS", "AMS-XMN");
		assertThat(result.segments()).extracting(segment -> segment.scheduledDeparture())
				.containsExactly("2026-08-04T17:30", "2026-08-04T21:40");
		assertThat(result.segments().get(1).scheduledArrival()).isEqualTo("2026-08-05T14:20");
		assertThat(result.segments()).allSatisfy(segment ->
				assertThat(segment.flightNumber()).isNotNull());
		assertThat(DocumentResultClassifier.classify(result)).isEqualTo(DocumentResultClassification.USABLE);
		assertFocusedFields(requester, "document_order", "flight_number");
	}

	@Test
	void degradedRouteOnlySegmentUsesFocusedPassToRecoverMf825() throws Exception {
		JsonNode first = documentResponse("""
				[{"departure_date":"2026-09-05","departure_airport":"XMN","arrival_airport":"CDG"}]
				""", oneCandidate("MF825"), 1, """
				[{"departure_date":{"confidence":0.99},"departure_airport":{"confidence":0.99},"arrival_airport":{"confidence":0.99}}]
				""");
		JsonNode evidence = evidenceResponse(oneCandidate("MF825"), 1, """
				[{"document_order":0,"departure_airport_raw":"XMN","arrival_airport_raw":"CDG"}]
				""");
		JsonNode focused = focusedResponse("""
				[{"document_order":0,"flight_number":"MF 825"}]
				""", """
				[{"flight_number":{"confidence":0.98}}]
				""");
		RecordingRequester requester = new RecordingRequester(first, evidence, focused);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 2 }, "synthetic.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("MF825");
			assertThat(segment.departureDate()).isEqualTo(LocalDate.of(2026, 9, 5));
			assertThat(segment.departureAirport()).isEqualTo("XMN");
			assertThat(segment.arrivalAirport()).isEqualTo("CDG");
		});
		assertThat(requester.stages).containsExactly("first-pass", "raw-evidence", "focused-pass");
		assertFocusedFields(requester, "document_order", "flight_number");
	}

	@Test
	void focusedIdentityConflictDoesNotOverwriteOrInsertBesideFirstPassEvidence() throws Exception {
		JsonNode first = documentResponse("""
				[{"flight_number":"MF825","departure_date":"2026-09-05","departure_airport":"XMN","arrival_airport":"CDG"}]
				""", oneCandidate("AF1668"), 1, """
				[{"flight_number":{"confidence":0.99},"departure_date":{"confidence":0.99},"departure_airport":{"confidence":0.99},"arrival_airport":{"confidence":0.99}}]
				""");
		JsonNode evidence = evidenceResponse(oneCandidate("AF1668"), 1, """
				[{"document_order":0,"flight_number_raw":"AF1668","departure_airport_raw":"XMN","arrival_airport_raw":"CDG"}]
				""");
		JsonNode focused = focusedResponse("""
				[{"document_order":0,"flight_number":"AF1668"}]
				""", """
				[{"flight_number":{"confidence":0.98}}]
				""");
		RecordingRequester requester = new RecordingRequester(first, evidence, focused);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 3 }, "synthetic.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("MF825");
			assertThat(segment.departureAirport()).isEqualTo("XMN");
		});
		assertThat(result.needsReviewFields()).contains("flight_number");
		assertFocusedFields(requester, "document_order", "flight_number");
	}

	@Test
	void rawCorroboratedFocusedValueCorrectsALowConfidenceFirstPassIdentity() throws Exception {
		JsonNode first = documentResponse("""
				[{"flight_number":"MF826","departure_date":"2026-09-05","departure_airport":"XMN","arrival_airport":"CDG"}]
				""", oneCandidate("MF825"), 1, """
				[{"flight_number":{"confidence":0.50},"departure_date":{"confidence":0.99},"departure_airport":{"confidence":0.99},"arrival_airport":{"confidence":0.99}}]
				""");
		JsonNode evidence = evidenceResponse(oneCandidate("MF825"), 1, """
				[{"document_order":0,"flight_number_raw":"MF825","departure_airport_raw":"XMN","arrival_airport_raw":"CDG"}]
				""");
		JsonNode focused = focusedResponse("""
				[{"document_order":0,"flight_number":"MF825"}]
				""", """
				[{"flight_number":{"confidence":0.98}}]
				""");
		RecordingRequester requester = new RecordingRequester(first, evidence, focused);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 10 }, "synthetic.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("MF825");
			assertThat(segment.fields().get("flight_number").value()).isEqualTo("MF825");
			assertThat(segment.fields().get("flight_number").confidence()).isEqualTo(0.98d);
		});
		assertThat(result.needsReviewFields()).contains("flight_number");
	}

	@Test
	void scheduledDepartureSuppliesLocalDateWithoutRecoveryOrArrivalInference() throws Exception {
		JsonNode first = documentResponse("""
				[{"flight_number":"KL1036","departure_airport":"MAN","arrival_airport":"AMS",
				  "scheduled_departure":"2026-08-04T17:30","scheduled_arrival":"2026-08-05T14:20"}]
				""", oneCandidate("KL1036"), 1, """
				[{"flight_number":{"confidence":0.99},"scheduled_departure":{"confidence":0.99},"scheduled_arrival":{"confidence":0.99}}]
				""");
		RecordingRequester requester = new RecordingRequester(first);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 4 }, "synthetic.png", "image/png");

		assertThat(result.departureDate()).isEqualTo(LocalDate.of(2026, 8, 4));
		assertThat(result.scheduledArrival()).isEqualTo("2026-08-05T14:20");
		assertThat(requester.stages).containsExactly("first-pass");
	}

	@Test
	void coverageCountMismatchRecoversAnOmittedSecondLegAndRejectsNoiseCandidates() throws Exception {
		String noisyCandidates = """
				[{"value":"MF825","document_order":0,"context":"ROUTE"},
				 {"value":"ABC123","document_order":0,"context":"OTHER"},
				 {"value":"123456","document_order":0,"context":"ROUTE"},
				 {"value":"AF1668","document_order":1,"context":"ROUTE"},
				 {"value":"22A","document_order":1,"context":"FLIGHT_LABEL"}]
				""";
		JsonNode first = documentResponse("""
				[{"flight_number":"MF825","departure_date":"2026-09-05","departure_airport":"XMN","arrival_airport":"CDG","scheduled_departure":"2026-09-05T00:10"}]
				""", noisyCandidates, 2, """
				[{"flight_number":{"confidence":0.99},"departure_date":{"confidence":0.99},"scheduled_departure":{"confidence":0.99}}]
				""");
		JsonNode evidence = evidenceResponse(noisyCandidates, 2, """
				[{"document_order":0,"flight_number_raw":"MF825","departure_airport_raw":"XMN","arrival_airport_raw":"CDG"},
				 {"document_order":1,"flight_number_raw":"AF1668","departure_date_raw":"2026-09-05",
				  "departure_airport_raw":"CDG","arrival_airport_raw":"MAN",
				  "scheduled_departure_raw":"2026-09-05T09:25"}]
				""");
		JsonNode focused = focusedResponse("""
				[{"document_order":1,"flight_number":"AF1668","departure_date":"2026-09-05","departure_airport":"CDG","arrival_airport":"MAN","scheduled_departure":"2026-09-05T09:25"}]
				""", """
				[{"flight_number":{"confidence":0.98},"departure_date":{"confidence":0.98},"departure_airport":{"confidence":0.98},"arrival_airport":{"confidence":0.98},"scheduled_departure":{"confidence":0.98}}]
				""");
		RecordingRequester requester = new RecordingRequester(first, evidence, focused);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 5 }, "synthetic.png", "image/png");

		assertThat(result.segments()).hasSize(2);
		assertThat(result.segments()).extracting(segment -> segment.flightNumber())
				.containsExactly("MF825", "AF1668");
		assertThat(result.segments()).extracting(segment -> segment.departureDate())
				.containsExactly(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5));
		assertThat(requester.stages).containsExactly("first-pass", "raw-evidence", "focused-pass");
	}

	@Test
	void insertedMiddleLegDropsFocusedFieldsThatHaveNoRawEvidenceSupport() throws Exception {
		String candidates = threeCandidates("KL1036", "MF812", "AF1668");
		JsonNode first = documentResponse("""
				[{"flight_number":"KL1036","departure_date":"2026-08-04","departure_airport":"MAN","arrival_airport":"AMS"},
				 {"flight_number":"AF1668","departure_date":"2026-09-05","departure_airport":"CDG","arrival_airport":"MAN"}]
				""", candidates, 3, """
				[{"flight_number":{"confidence":0.99},"departure_date":{"confidence":0.99}},
				 {"flight_number":{"confidence":0.99},"departure_date":{"confidence":0.99}}]
				""");
		JsonNode evidence = evidenceResponse(candidates, 3, """
				[{"document_order":1,"flight_number_raw":"MF812"}]
				""");
		JsonNode focused = focusedResponse("""
				[{"document_order":1,"flight_number":"MF812","departure_date":"2026-08-04",
				  "departure_airport":"AMS","arrival_airport":"XMN","scheduled_departure":"2026-08-04T21:40"}]
				""", """
				[{"flight_number":{"confidence":0.99},"departure_date":{"confidence":0.99},
				  "departure_airport":{"confidence":0.99},"arrival_airport":{"confidence":0.99},
				  "scheduled_departure":{"confidence":0.99}}]
				""");
		RecordingRequester requester = new RecordingRequester(first, evidence, focused);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 11 }, "synthetic.png", "image/png");

		assertThat(result.segments()).extracting(segment -> segment.flightNumber())
				.containsExactly("KL1036", "MF812", "AF1668");
		assertThat(result.segments().get(1)).satisfies(segment -> {
			assertThat(segment.departureDate()).isNull();
			assertThat(segment.departureAirport()).isNull();
			assertThat(segment.arrivalAirport()).isNull();
			assertThat(segment.scheduledDeparture()).isNull();
		});
		assertThat(result.needsReviewFields()).contains("segments[1].departure_date");
	}

	@Test
	void emptyUnknownFirstPassAuditsRawEvidenceButSkipsFocusedWhenNoFlightTargetExists() throws Exception {
		JsonNode first = mapper.readTree("""
				{"output":{"data":{"document_type":"UNKNOWN","flight_number_candidates":[],
				"visible_flight_segment_count":0,"segments":[]},
				"metadata":{"document_type":{"confidence":0.99},"segments":[]}}}
				""");
		JsonNode evidence = evidenceResponse("[]", 0, "[]");
		RecordingRequester requester = new RecordingRequester(first, evidence);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 6 }, "synthetic.png", "image/png");

		assertThat(result.segments()).isEmpty();
		assertThat(requester.stages).containsExactly("first-pass", "raw-evidence");
	}

	@Test
	void focusedFlightNumberMustMatchTheRawCandidateAtTheSameDocumentOrder() throws Exception {
		JsonNode first = documentResponse("""
				[{"departure_date":"2026-09-05","departure_airport":"XMN","arrival_airport":"CDG"}]
				""", oneCandidate("MF825"), 1, """
				[{"departure_date":{"confidence":0.99},"departure_airport":{"confidence":0.99},"arrival_airport":{"confidence":0.99}}]
				""");
		JsonNode evidence = evidenceResponse(oneCandidate("MF825"), 1, """
				[{"document_order":0,"flight_number_raw":"MF825","departure_airport_raw":"XMN","arrival_airport_raw":"CDG"}]
				""");
		JsonNode focused = focusedResponse("""
				[{"document_order":0,"flight_number":"MF826"}]
				""", """
				[{"flight_number":{"confidence":0.99}}]
				""");
		RecordingRequester requester = new RecordingRequester(first, evidence, focused);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 7 }, "synthetic.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment ->
				assertThat(segment.flightNumber()).isNull());
		assertThat(result.needsReviewFields()).contains("flight_number");
		assertThat(requester.stages).containsExactly("first-pass", "raw-evidence", "focused-pass");
	}

	@Test
	void focusedPassCannotInventAYearForUnparseableRawDepartureDateEvidence() throws Exception {
		JsonNode first = documentResponse("""
				[{"flight_number":"MF825","departure_airport":"XMN","arrival_airport":"CDG"}]
				""", oneCandidate("MF825"), 1, """
				[{"flight_number":{"confidence":0.99},"departure_airport":{"confidence":0.99},"arrival_airport":{"confidence":0.99}}]
				""");
		JsonNode evidence = evidenceResponse(oneCandidate("MF825"), 1, """
				[{"document_order":0,"flight_number_raw":"MF825","departure_date_raw":"9月5日",
				  "departure_airport_raw":"XMN","arrival_airport_raw":"CDG"}]
				""");
		JsonNode focused = focusedResponse("""
				[{"document_order":0,"departure_date":"2026-09-05"}]
				""", """
				[{"departure_date":{"confidence":0.99}}]
				""");
		RecordingRequester requester = new RecordingRequester(first, evidence, focused);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 8 }, "synthetic.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("MF825");
			assertThat(segment.departureDate()).isNull();
			assertThat(segment.fields().get("departure_date").rawValue()).isNull();
		});
		assertThat(result.needsReviewFields()).contains("departure_date");
	}

	private void assertFixedSample(String name, List<String> flights, List<String> routes,
			List<LocalDate> dates, List<String> scheduledDepartures) throws Exception {
		RecordingRequester requester = new RecordingRequester(fixture(name));
		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 9 }, "synthetic.png", "image/png");

		assertThat(requester.stages).containsExactly("first-pass");
		assertThat(result.segments()).hasSize(2);
		assertThat(result.segments()).extracting(segment -> segment.flightNumber())
				.containsExactlyElementsOf(flights);
		assertThat(result.segments()).extracting(segment ->
				segment.departureAirport() + "-" + segment.arrivalAirport())
				.containsExactlyElementsOf(routes);
		assertThat(result.segments()).extracting(segment -> segment.departureDate())
				.containsExactlyElementsOf(dates);
		assertThat(result.segments()).extracting(segment -> segment.scheduledDeparture())
				.containsExactlyElementsOf(scheduledDepartures);
		assertThat(result.segments()).allSatisfy(segment ->
				assertThat(segment.fields().get("flight_number").rawValue()).contains(" "));
		String publicJson = mapper.writeValueAsString(result);
		assertThat(publicJson).doesNotContain("rawValue", "高崎机场", "Manchester Airport");
	}

	@SuppressWarnings("unchecked")
	private void assertFocusedFields(RecordingRequester requester, String... expected) {
		Map<String, Object> instructions = requester.instructions.get(2);
		Map<String, Object> schema = (Map<String, Object>) instructions.get("schema");
		Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
		Map<String, Object> segments = (Map<String, Object>) properties.get("segments");
		Map<String, Object> items = (Map<String, Object>) segments.get("items");
		Map<String, Object> fields = (Map<String, Object>) items.get("properties");
		assertThat(fields).containsOnlyKeys(expected);
	}

	private NutrientBoardingPassDocumentProcessor processor(RecordingRequester requester) {
		return new NutrientBoardingPassDocumentProcessor(mapper, CLOCK, requester);
	}

	private JsonNode fixture(String name) throws Exception {
		String json = new ClassPathResource("fixtures/nutrient/" + name)
				.getContentAsString(StandardCharsets.UTF_8);
		return mapper.readTree(json);
	}

	private JsonNode documentResponse(String segments, String candidates, int visibleCount,
			String segmentMetadata) throws Exception {
		return mapper.readTree("""
				{"output":{"data":{"document_type":"ITINERARY","flight_number_candidates":%s,
				"visible_flight_segment_count":%d,"segments":%s},"metadata":{"document_type":{"confidence":0.99},
				"segments":%s}}}
				""".formatted(candidates, visibleCount, segments, segmentMetadata));
	}

	private JsonNode evidenceResponse(String candidates, int visibleCount, String evidenceSegments)
			throws Exception {
		return mapper.readTree("""
				{"output":{"data":{"flight_number_candidates":%s,"visible_flight_segment_count":%d,
				"evidence_segments":%s},"metadata":{}}}
				""".formatted(candidates, visibleCount, evidenceSegments));
	}

	private JsonNode focusedResponse(String segments, String segmentMetadata) throws Exception {
		return mapper.readTree("""
				{"output":{"data":{"segments":%s},"metadata":{"segments":%s}}}
				""".formatted(segments, segmentMetadata));
	}

	private String oneCandidate(String flightNumber) {
		return """
				[{"value":"%s","document_order":0,"context":"ROUTE"}]
				""".formatted(flightNumber);
	}

	private String twoCandidates(String first, String second) {
		return """
				[{"value":"%s","document_order":0,"context":"ROUTE"},
				 {"value":"%s","document_order":1,"context":"ROUTE"}]
				""".formatted(first, second);
	}

	private String threeCandidates(String first, String second, String third) {
		return """
				[{"value":"%s","document_order":0,"context":"ROUTE"},
				 {"value":"%s","document_order":1,"context":"ROUTE"},
				 {"value":"%s","document_order":2,"context":"ROUTE"}]
				""".formatted(first, second, third);
	}

	private static final class RecordingRequester
			implements NutrientBoardingPassDocumentProcessor.ExtractionRequester {
		private final Deque<JsonNode> responses = new ArrayDeque<>();
		private final List<String> stages = new ArrayList<>();
		private final List<Map<String, Object>> instructions = new ArrayList<>();

		private RecordingRequester(JsonNode... responses) {
			this.responses.addAll(List.of(responses));
		}

		@Override
		public JsonNode request(byte[] documentBytes, String filename, String contentType,
				String stage, Map<String, Object> instructions) {
			stages.add(stage);
			this.instructions.add(instructions);
			if (responses.isEmpty()) {
				throw new AssertionError("Unexpected Nutrient request: " + stage);
			}
			return responses.removeFirst();
		}
	}
}
