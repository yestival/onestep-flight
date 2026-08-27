package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;
import com.selene.onestepflight.dto.BoundingBox;
import com.selene.onestepflight.dto.DocumentFieldCandidate;
import com.selene.onestepflight.dto.DocumentFlightSegment;
import com.selene.onestepflight.dto.DocumentReviewIssue;
import com.selene.onestepflight.dto.DocumentType;
import com.selene.onestepflight.service.airline.AirlineRegistry;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DocumentReviewAndDebugTest {

	private static final Clock CLOCK = Clock.fixed(
			Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC);
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void onlyDistinctUnresolvedCriticalValuesBlock() throws Exception {
		DocumentFieldCandidate understand = candidate("flight_number", "BA 117", "BA117",
				DocumentFieldCandidate.Source.UNDERSTAND);
		DocumentFieldCandidate structureSame = candidate("flight_number", "BA117", "BA117",
				DocumentFieldCandidate.Source.STRUCTURE);
		DocumentFieldCandidate structureOther = candidate("flight_number", "BA118", "BA118",
				DocumentFieldCandidate.Source.STRUCTURE);

		DocumentReviewIssue corroborated = new DocumentReviewIssue(0, "flight_number",
				DocumentReviewIssue.Kind.ACTIONABLE, true,
				List.of(understand, structureSame), null, "MULTIPLE_UNRESOLVED_CANDIDATES");
		DocumentReviewIssue unresolved = new DocumentReviewIssue(0, "flight_number",
				DocumentReviewIssue.Kind.ACTIONABLE, false,
				List.of(understand, structureOther), null, "MULTIPLE_UNRESOLVED_CANDIDATES");
		DocumentReviewIssue auxiliary = new DocumentReviewIssue(0, "departure_time",
				DocumentReviewIssue.Kind.ACTIONABLE, true,
				List.of(candidate("departure_time", "09:20", "09:20",
						DocumentFieldCandidate.Source.UNDERSTAND),
						candidate("departure_time", "09:25", "09:25",
								DocumentFieldCandidate.Source.STRUCTURE)),
				null, "AUXILIARY_EVIDENCE_DISAGREEMENT");
		DocumentReviewIssue manualFallback = new DocumentReviewIssue(0, "departure_date",
				DocumentReviewIssue.Kind.ACTIONABLE, true, List.of(), null,
				"LEGACY_CONFLICT_REQUIRES_MANUAL_INPUT");
		DocumentReviewIssue loneCandidate = new DocumentReviewIssue(0, "departure_date",
				DocumentReviewIssue.Kind.ACTIONABLE, true,
				List.of(candidate("departure_date", "2026-08-04", "2026-08-04",
						DocumentFieldCandidate.Source.STRUCTURE)), null,
				"MULTIPLE_UNRESOLVED_CANDIDATES");

		assertThat(corroborated.blocking()).isFalse();
		assertThat(corroborated.kind()).isEqualTo(DocumentReviewIssue.Kind.ADVISORY);
		assertThat(unresolved.blocking()).isTrue();
		assertThat(unresolved.kind()).isEqualTo(DocumentReviewIssue.Kind.ACTIONABLE);
		assertThat(auxiliary.blocking()).isFalse();
		assertThat(auxiliary.kind()).isEqualTo(DocumentReviewIssue.Kind.ADVISORY);
		assertThat(manualFallback.blocking()).isTrue();
		assertThat(loneCandidate.blocking()).isFalse();
		assertThat(mapper.writeValueAsString(unresolved))
				.contains("\"value\":\"BA117\"")
				.doesNotContain("rawValue", "BA 117");
	}

	@Test
	void singleLowConfidenceValidFlightIsRetainedAndLookupReady() {
		DocumentFlightSegment segment = new DocumentFlightSegment("Q0999",
				LocalDate.of(2026, 8, 4), "08-04", null, null, null, null, null,
				null, null, null, 0.25d, null, null, Map.of(),
				DocumentFlightSegment.FlightNumberConfidence.LOW, true, false,
				DocumentFlightSegment.DateSource.EXPLICIT_FULL_DATE);
		BoardingPassExtractionResponse response = new BoardingPassExtractionResponse(
				DocumentType.ITINERARY, null, null, null, null, null, null, null,
				null, null, null, false, false, Map.of(), List.of(segment), List.of(),
				"TEST", Instant.EPOCH);

		assertThat(segment.flightNumber()).isEqualTo("Q0999");
		assertThat(segment.requiresFlightNumberConfirmation()).isFalse();
		assertThat(DocumentResultClassifier.classify(response))
				.isEqualTo(DocumentResultClassification.USABLE);
	}

	@Test
	void singleChannelDateConflictProducesActionableCandidates() throws Exception {
		RecordingRequester requester = new RecordingRequester(
				mapper.readTree("""
						{"output":{"data":{"document_type":"ITINERARY","segments":[{
						  "flight_number":"BA117","departure_date":"2026-08-04",
						  "scheduled_departure":"2026-08-05T09:25:00"}]},"metadata":{}}}
						"""), mapper.readTree("[]"));
		BoardingPassExtractionResponse result = processor(requester, false).extract(
				new byte[] { 4, 2 }, "date-conflict.png", "image/png");

		assertThat(result.reviewIssues()).filteredOn(issue -> "departure_date".equals(issue.field()))
				.singleElement().satisfies(issue -> {
			assertThat(issue.field()).isEqualTo("departure_date");
			assertThat(issue.blocking()).isTrue();
			assertThat(issue.selectedValue()).isNull();
			assertThat(issue.candidates()).extracting(DocumentFieldCandidate::normalizedValue)
					.contains("2026-08-04", "2026-08-05");
		});
		assertThat(requester.stages).containsExactlyInAnyOrder("understand", "structure");
		assertThat(requester.byteReferences).allSatisfy(bytes -> assertThat(bytes)
				.isSameAs(requester.byteReferences.get(0)));
	}

	@Test
	void debugAuditIsOptInAllowListedAndIncludesUnboundStructureTokens() throws Exception {
		JsonNode understand = mapper.readTree("""
				{"output":{"data":{"document_type":"ITINERARY","segments":[{
				  "flight_number":"BA117","airline_name":"British Airways",
				  "departure_date":"2026-08-04","scheduled_departure":"09:25",
				  "departure_airport":"LHR","arrival_airport":"JFK",
				  "passenger_name":"PRIVATE-TRAVELER","ticket_number":"SECRET-TICKET"}]},
				  "metadata":{}}}
				""");
		JsonNode structure = mapper.readTree("""
				[{"type":"heading","text":"2026-08-04","pageIndex":0,"bounds":[20,20,80,20]},
				  {"type":"token","text":"LHR","pageIndex":0,"bounds":[100,100,45,20]},
				  {"type":"time","text":"09:25","pageIndex":0,"bounds":[20,110,55,20]},
				  {"type":"token","text":"BA117","pageIndex":0,"bounds":[240,150,70,20]},
				  {"type":"token","text":"JFK","pageIndex":0,"bounds":[100,220,45,20]},
				  {"type":"time","text":"11:00","pageIndex":0,"bounds":[20,230,55,20]},
				  {"type":"time","text":"21:40","pageIndex":0,"bounds":[20,300,55,20]}]
				""");
		RecordingRequester requester = new RecordingRequester(understand, structure);
		Logger logger = (Logger) LoggerFactory.getLogger(NutrientBoardingPassDocumentProcessor.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		BoardingPassExtractionResponse result;
		try {
			result = processor(requester, true).extract(
					new byte[] { 9 }, "private-name.png", "image/png");
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}

		List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
		assertThat(messages).anyMatch(message -> message.contains("documentExtractionDebug enabled=true"));
		assertThat(messages).anyMatch(message -> message.contains("fieldType=flight_number")
				&& message.contains("normalizedValue=BA117"));
		assertThat(messages).anyMatch(message -> message.contains("normalizedValue=21:40")
				&& message.contains("segment=0"));
		assertThat(messages).allSatisfy(message -> assertThat(message)
				.doesNotContain("PRIVATE-TRAVELER", "SECRET-TICKET", "private-name.png"));
		assertThat(result.reviewIssues()).anySatisfy(issue -> {
			assertThat(issue.field()).isEqualTo("arrival_time");
			assertThat(issue.blocking()).isFalse();
			assertThat(issue.candidates()).extracting(DocumentFieldCandidate::normalizedValue)
					.contains("21:40");
		});
	}

	@Test
	void defaultDebugModeNeverEmitsDetailedAuditRecords() throws Exception {
		RecordingRequester requester = new RecordingRequester(
				mapper.readTree("{\"output\":{\"data\":{\"document_type\":\"ITINERARY\",\"segments\":[]},\"metadata\":{}}}"),
				mapper.readTree("[]"));
		Logger logger = (Logger) LoggerFactory.getLogger(NutrientBoardingPassDocumentProcessor.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			processor(requester, false).extract(new byte[] { 3 }, "no-debug.png", "image/png");
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}

		assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
				.noneMatch(message -> message.contains("documentExtractionDebug"));
	}

	@Test
	void airlineNameDoesNotManufactureAnAbsentSameSuffixFlightCandidate() throws Exception {
		JsonNode structure = mapper.readTree("""
				[{"type":"heading","text":"2026-08-04","pageIndex":0,"bounds":[20,20,80,20]},
				  {"type":"token","text":"LHR","pageIndex":0,"bounds":[80,100,45,20],"confidence":0.9},
				  {"type":"token","text":"Air France","pageIndex":0,"bounds":[150,150,80,20],"confidence":0.98},
				  {"type":"token","text":"F1 668","pageIndex":0,"bounds":[240,150,70,20],"confidence":0.40},
				  {"type":"token","text":"CDG","pageIndex":0,"bounds":[350,220,45,20],"confidence":0.9}]
				""");
		RecordingRequester requester = new RecordingRequester(
				mapper.readTree("{\"output\":{\"data\":{\"document_type\":\"ITINERARY\",\"segments\":[]},\"metadata\":{}}}"),
				structure);

		BoardingPassExtractionResponse result = processor(requester, false).extract(
				new byte[] { 7 }, "generic-correction.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("F1668");
			assertThat(segment.departureAirport()).isNull();
			assertThat(segment.arrivalAirport()).isNull();
		});
	}

	@Test
	void unexpectedUnderstandRuntimeFailureStillMakesOneStructureCall() throws Exception {
		List<String> stages = java.util.Collections.synchronizedList(new ArrayList<>());
		NutrientBoardingPassDocumentProcessor.ExtractionRequester requester =
				(bytes, filename, contentType, stage, instructions) -> {
					stages.add(stage);
					if ("understand".equals(stage)) throw new IllegalStateException("synthetic failure");
					return mapper.readTree("[]");
				};
		NutrientBoardingPassDocumentProcessor processor = new NutrientBoardingPassDocumentProcessor(
				mapper, CLOCK, requester, AirlineRegistry.loadDefault(), false);

		processor.extract(new byte[] { 8 }, "runtime-failure.png", "image/png");

		assertThat(stages).containsExactlyInAnyOrder("structure", "understand");
	}

	@Test
	void unicodeHyphensAreNormalizedAcrossDocumentCandidates() {
		NutrientBoardingPassDocumentProcessor processor = processor(
				new RecordingRequester(mapper.nullNode(), mapper.nullNode()), false);

		assertThat(processor.normalizeFlightNumber("BA‑117")).isEqualTo("BA117");
		assertThat(processor.normalizeFlightNumber("QF–12")).isEqualTo("QF12");
		assertThat(new DocumentFlightSegment("QF—12", LocalDate.of(2026, 8, 4),
				"SYD", "SIN", null, null, null, null, null, null,
				0.4d, null, null, Map.of()).flightNumber()).isEqualTo("QF12");
	}

	@Test
	void directFlightLikeValueBesideSensitiveLabelIsNotAnAnchor() throws Exception {
		JsonNode structure = mapper.readTree("""
				[{"type":"heading","text":"2026-11-12","pageIndex":0,"bounds":[20,20,100,20]},
				  {"type":"token","text":"DFW","pageIndex":0,"bounds":[40,60,45,20]},
				  {"type":"label","text":"PNR","pageIndex":0,"bounds":[20,100,50,20]},
				  {"type":"value","text":"QR908","pageIndex":0,"bounds":[90,100,70,20]},
				  {"type":"token","text":"DOH","pageIndex":0,"bounds":[40,160,45,20]}]
				""");
		BoardingPassExtractionResponse result = processor(new RecordingRequester(
				mapper.readTree("{\"output\":{\"data\":{\"document_type\":\"ITINERARY\",\"segments\":[]},\"metadata\":{}}}"),
				structure), false).extract(new byte[] { 1 }, "sensitive-context.png", "image/png");

		assertThat(result.segments()).isEmpty();
	}

	@Test
	void repeatedAirportNodeNeverCreatesASelfLoop() throws Exception {
		JsonNode structure = mapper.readTree("""
				[{"type":"card","pageIndex":0,"bounds":[0,0,500,260],"children":[
				  {"type":"heading","text":"2026-11-12","pageIndex":0,"bounds":[20,20,100,20]},
				  {"type":"token","text":"ORD","pageIndex":0,"bounds":[50,60,45,20]},
				  {"type":"token","text":"UA321","pageIndex":0,"bounds":[220,100,70,20]},
				  {"type":"token","text":"ORD","pageIndex":0,"bounds":[50,140,45,20]},
				  {"type":"token","text":"ORD","pageIndex":0,"bounds":[50,160,45,20]},
				  {"type":"token","text":"SFO","pageIndex":0,"bounds":[50,200,45,20]}]}]
				""");
		BoardingPassExtractionResponse result = processor(new RecordingRequester(
				mapper.readTree("{\"output\":{\"data\":{\"document_type\":\"ITINERARY\",\"segments\":[]},\"metadata\":{}}}"),
				structure), false).extract(new byte[] { 2 }, "repeat-node.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.departureAirport()).isEqualTo("ORD");
			assertThat(segment.arrivalAirport()).isEqualTo("SFO");
		});
	}

	@Test
	void equidistantTransferTimeStaysAdvisoryInsteadOfFillingBothLegs() throws Exception {
		JsonNode structure = mapper.readTree("""
				[{"type":"heading","text":"2026-11-12","pageIndex":0,"bounds":[20,20,100,20]},
				  {"type":"token","text":"SEA","pageIndex":0,"bounds":[50,60,45,20]},
				  {"type":"token","text":"UA321","pageIndex":0,"bounds":[220,100,70,20]},
				  {"type":"token","text":"DEN","pageIndex":0,"bounds":[50,140,45,20]},
				  {"type":"time","text":"12:00","pageIndex":0,"bounds":[50,200,55,20]},
				  {"type":"token","text":"DL456","pageIndex":0,"bounds":[220,300,70,20]},
				  {"type":"token","text":"BOS","pageIndex":0,"bounds":[50,340,45,20]}]
				""");
		BoardingPassExtractionResponse result = processor(new RecordingRequester(
				mapper.readTree("{\"output\":{\"data\":{\"document_type\":\"ITINERARY\",\"segments\":[]},\"metadata\":{}}}"),
				structure), false).extract(new byte[] { 5 }, "ambiguous-time.png", "image/png");

		assertThat(result.segments()).hasSize(2);
		assertThat(result.segments().get(0).scheduledArrival()).isNull();
		assertThat(result.segments().get(1).scheduledDeparture()).isNull();
		assertThat(result.reviewIssues()).anySatisfy(issue -> {
			assertThat(issue.segmentIndex()).isEqualTo(1);
			assertThat(issue.field()).isEqualTo("departure_time");
			assertThat(issue.blocking()).isFalse();
			assertThat(issue.reason()).isEqualTo("AMBIGUOUS_TIME_ROLE");
			assertThat(issue.candidates()).extracting(DocumentFieldCandidate::value)
					.contains("12:00");
		});
	}

	private DocumentFieldCandidate candidate(String field, String raw, String normalized,
			DocumentFieldCandidate.Source source) {
		return new DocumentFieldCandidate(field, raw, normalized, source, 0.7d, 0,
				new BoundingBox(1, 2, 3, 4), DocumentFieldCandidate.AnchorType.FLIGHT_CARD,
				0, DocumentFieldCandidate.Disposition.CANDIDATE, "TEST_EVIDENCE");
	}

	private NutrientBoardingPassDocumentProcessor processor(RecordingRequester requester,
			boolean debug) {
		return new NutrientBoardingPassDocumentProcessor(mapper, CLOCK, requester,
				AirlineRegistry.loadDefault(), debug);
	}

	private static final class RecordingRequester
			implements NutrientBoardingPassDocumentProcessor.ExtractionRequester {
		private final JsonNode understand;
		private final JsonNode structure;
		private final List<String> stages = java.util.Collections.synchronizedList(new ArrayList<>());
		private final List<byte[]> byteReferences = java.util.Collections.synchronizedList(new ArrayList<>());

		private RecordingRequester(JsonNode understand, JsonNode structure) {
			this.understand = understand;
			this.structure = structure;
		}

		@Override
		public JsonNode request(byte[] bytes, String filename, String contentType,
				String stage, Map<String, Object> instructions) {
			stages.add(stage);
			byteReferences.add(bytes);
			return "understand".equals(stage) ? understand : structure;
		}
	}
}
