package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClientException;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DocumentExtractionPipelineTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC);
	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void kl1036AndMf812SpatialFixtureRestoresBothLegsWithoutCrossBinding() throws Exception {
		JsonNode understand = understandResponse("""
				[{"flight_number":"KL1036","departure_date":"8月4日",
				  "departure_airport":"MAN","arrival_airport":"XMN",
				  "passenger_name":"PRIVATE-PASSENGER","ticket_number":"SECRET-TICKET"}]
				""");
		RecordingRequester requester = new RecordingRequester(understand,
				fixture("spatial-kl1036-mf812.json"));

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 1, 2, 3 }, "outbound.png", "image/png");

		assertThat(result.segments()).hasSize(2);
		assertThat(result.segments()).extracting(segment -> segment.flightNumber())
				.containsExactly("KL1036", "MF812");
		assertThat(result.segments()).extracting(segment ->
				segment.departureAirport() + "-" + segment.arrivalAirport())
				.containsExactly("MAN-AMS", "AMS-XMN")
				.doesNotContain("MAN-XMN");
		assertThat(result.segments()).extracting(segment -> segment.departureDate())
				.containsExactly(LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 4));
		assertThat(result.segments()).extracting(segment -> segment.departureMonthDay())
				.containsExactly("08-04", "08-04");
		assertThat(result.segments()).extracting(segment -> segment.scheduledDeparture())
				.containsExactly("17:30", "21:40");
		assertThat(result.segments()).extracting(segment -> segment.scheduledArrival())
				.containsExactly("19:50", "14:20");
		assertThat(result.segments()).extracting(segment -> segment.terminal())
				.containsExactly("T2", null);
		assertThat(result.segments()).allSatisfy(segment -> {
			assertThat(segment.dateSource()).isEqualTo(
					com.selene.onestepflight.dto.DocumentFlightSegment.DateSource.CURRENT_YEAR_FROM_MONTH_DAY);
			assertThat(segment.requiresFlightNumberConfirmation()).isFalse();
		});
		assertThat(result.needsReviewFields()).doesNotContain(
				"segments[0].departure_date", "segments[1].departure_date");

		String publicJson = mapper.writeValueAsString(result);
		assertThat(publicJson).doesNotContain(
				"PRIVATE-PASSENGER", "SECRET-TICKET", "passenger_name", "ticket_number",
				"rawValue", "elements", "8月4日");
		assertExactlyTwoCalls(requester, "outbound.png", "image/png", new byte[] { 1, 2, 3 });
	}

	@Test
	void mf825AndAf1668SpatialFixtureRestoresBothLegsWithoutCrossBinding() throws Exception {
		JsonNode understand = understandResponse("""
				[{"flight_number":"AF1668","departure_date":"9月5日",
				  "departure_airport":"XMN","arrival_airport":"CDG"}]
				""");
		RecordingRequester requester = new RecordingRequester(understand,
				fixture("spatial-mf825-af1668.json"));

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 4, 5, 6 }, "return.jpg", "image/jpeg");

		assertThat(result.segments()).hasSize(2);
		assertThat(result.segments()).extracting(segment -> segment.flightNumber())
				.containsExactly("MF825", "AF1668");
		assertThat(result.segments()).extracting(segment ->
				segment.departureAirport() + "-" + segment.arrivalAirport())
				.containsExactly("XMN-CDG", "CDG-MAN");
		assertThat(result.segments().get(1)).satisfies(segment -> {
			assertThat(segment.departureAirport() + "-" + segment.arrivalAirport())
					.isNotEqualTo("XMN-CDG");
			assertThat(segment.departureMonthDay()).isEqualTo("09-05");
			assertThat(segment.departureDate()).isEqualTo(LocalDate.of(2026, 9, 5));
		});
		assertThat(result.segments()).extracting(segment -> segment.departureMonthDay())
				.containsExactly("09-05", "09-05");
		assertThat(result.segments()).extracting(segment -> segment.scheduledDeparture())
				.containsExactly("00:10", "09:25");
		assertThat(result.segments()).extracting(segment -> segment.scheduledArrival())
				.containsExactly("06:55", "10:00");
		assertThat(result.segments()).extracting(segment -> segment.terminal())
				.containsExactly("T3", "T2E");
		assertExactlyTwoCalls(requester, "return.jpg", "image/jpeg", new byte[] { 4, 5, 6 });
	}

	@Test
	void equalLengthCandidateListsUseStableUnionComplementFieldsAndKeepDifferentFlights() throws Exception {
		JsonNode understand = understandResponse("""
				[{"flight_number":"BA100","departure_date":"2026-10-01",
				  "departure_airport":"JFK","arrival_airport":"LAX"},
				 {"flight_number":"BA200","departure_date":"2026-10-01","seat":"12A"}]
				""");
		JsonNode structure = spatialResponse("""
				[{"type":"paragraph","text":"10月1日","bounds":{"x":40,"y":20,"width":80,"height":18},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"LHR","bounds":{"x":120,"y":100,"width":50,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"BA200","bounds":{"x":250,"y":140,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"CDG","bounds":{"x":120,"y":220,"width":50,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"CDG","bounds":{"x":120,"y":320,"width":50,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"BA300","bounds":{"x":250,"y":360,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"FCO","bounds":{"x":120,"y":450,"width":50,"height":20},"pageIndex":0,"confidence":0.99}]
				""");
		RecordingRequester requester = new RecordingRequester(understand, structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 7 }, "union.pdf", "application/pdf");

		assertThat(result.segments()).hasSize(3);
		assertThat(result.segments()).extracting(segment -> segment.flightNumber())
				.containsExactly("BA200", "BA300", "BA100");
		assertThat(result.segments()).extracting(segment -> segment.flightNumber()).doesNotHaveDuplicates();
		assertThat(result.segments().get(0)).satisfies(segment -> {
			assertThat(segment.departureAirport()).isEqualTo("LHR");
			assertThat(segment.arrivalAirport()).isEqualTo("CDG");
			assertThat(segment.departureDate()).isEqualTo(LocalDate.of(2026, 10, 1));
			assertThat(segment.departureMonthDay()).isEqualTo("10-01");
			assertThat(segment.seat()).isEqualTo("12A");
		});
		assertThat(result.segments().get(1)).satisfies(segment -> {
			assertThat(segment.departureAirport()).isEqualTo("CDG");
			assertThat(segment.arrivalAirport()).isEqualTo("FCO");
			assertThat(segment.departureMonthDay()).isEqualTo("10-01");
		});
		assertExactlyTwoCalls(requester, "union.pdf", "application/pdf", new byte[] { 7 });
	}

	@Test
	void understandFullDateConflictingWithSpatialMonthDayClearsDateAndMarksConflict() throws Exception {
		JsonNode understand = understandResponse("""
				[{"flight_number":"BA117","departure_date":"2026-08-04",
				  "departure_airport":"LHR","arrival_airport":"JFK"}]
				""");
		RecordingRequester requester = new RecordingRequester(understand,
				singleFlightSpatial("9月5日", "T4"));

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 14 }, "date-conflict-a.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("BA117");
			assertThat(segment.departureDate()).isNull();
			assertThat(segment.departureMonthDay()).isNull();
			assertThat(segment.fields().get("departure_date").value()).isNull();
		});
		assertThat(result.needsReviewFields()).contains("departure_date", "conflict.departure_date");
		assertExactlyTwoCalls(requester, "date-conflict-a.png", "image/png", new byte[] { 14 });
	}

	@Test
	void understandMonthDayConflictingWithSpatialFullDateClearsDateAndMarksConflict() throws Exception {
		JsonNode understand = understandResponse("""
				[{"flight_number":"BA117","departure_date":"8月4日",
				  "departure_airport":"LHR","arrival_airport":"JFK"}]
				""");
		RecordingRequester requester = new RecordingRequester(understand,
				singleFlightSpatial("2026-09-05", "T4"));

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 15 }, "date-conflict-b.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("BA117");
			assertThat(segment.departureDate()).isNull();
			assertThat(segment.departureMonthDay()).isNull();
			assertThat(segment.fields().get("departure_date").value()).isNull();
		});
		assertThat(result.needsReviewFields()).contains("departure_date", "conflict.departure_date");
		assertExactlyTwoCalls(requester, "date-conflict-b.png", "image/png", new byte[] { 15 });
	}

	@Test
	void visibleMonthDayConflictingWithScheduledDepartureDateClearsUnderstandDate() throws Exception {
		JsonNode understand = understandResponse("""
				[{"flight_number":"BA117","departure_date":"8月4日",
				  "departure_airport":"LHR","arrival_airport":"JFK",
				  "scheduled_departure":"2026-09-05T17:30:00+01:00"}]
				""");
		RecordingRequester requester = new RecordingRequester(understand, spatialResponse("[]"));

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 21 }, "understand-date-conflict.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("BA117");
			assertThat(segment.departureDate()).isNull();
			assertThat(segment.departureMonthDay()).isNull();
			assertThat(segment.scheduledDeparture()).isEqualTo("2026-09-05T17:30:00+01:00");
			assertThat(segment.fields().get("departure_date").value()).isNull();
		});
		assertThat(result.needsReviewFields()).contains("departure_date", "conflict.departure_date");
		assertExactlyTwoCalls(requester, "understand-date-conflict.png", "image/png", new byte[] { 21 });
	}

	@Test
	void sameFlightMergeRetainsArrivalTerminalEvidence() throws Exception {
		JsonNode understand = understandResponse("""
				[{"flight_number":"BA117","departure_date":"2026-08-04",
				  "departure_airport":"LHR","arrival_airport":"JFK",
				  "departure_terminal":"T2","arrival_terminal":"T4"}]
				""");
		RecordingRequester requester = new RecordingRequester(understand,
				singleFlightSpatial("2026-08-04", "T4"));

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 16 }, "terminal-match.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.terminal()).isEqualTo("T2");
			assertThat(segment.fields().get("arrival_terminal").value()).isEqualTo("T4");
			assertThat(segment.fields().get("arrival_terminal").sourceLabel())
					.contains("Structure Spatial");
		});
		assertThat(result.needsReviewFields()).doesNotContain("conflict.arrival_terminal");
		assertExactlyTwoCalls(requester, "terminal-match.png", "image/png", new byte[] { 16 });
	}

	@Test
	void arrivalTerminalConflictClearsEvidenceAndMarksReview() throws Exception {
		JsonNode understand = understandResponse("""
				[{"flight_number":"BA117","departure_date":"2026-08-04",
				  "departure_airport":"LHR","arrival_airport":"JFK",
				  "departure_terminal":"T2","arrival_terminal":"T5"}]
				""");
		RecordingRequester requester = new RecordingRequester(understand,
				singleFlightSpatial("2026-08-04", "T4"));

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 17 }, "terminal-conflict.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.terminal()).isEqualTo("T2");
			assertThat(segment.fields().get("arrival_terminal").value()).isNull();
		});
		assertThat(result.needsReviewFields()).contains("conflict.arrival_terminal");
		assertExactlyTwoCalls(requester, "terminal-conflict.png", "image/png", new byte[] { 17 });
	}

	@Test
	void repeatedSpatialFlightDisplayIsAuditedWithoutDuplicatingTheFinalSegment() throws Exception {
		JsonNode structure = spatialResponse("""
				[{"type":"paragraph","text":"8月4日","bounds":{"x":20,"y":20,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"LHR","bounds":{"x":100,"y":100,"width":50,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"BA117","bounds":{"x":240,"y":140,"width":70,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"JFK","bounds":{"x":100,"y":220,"width":50,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"BA117","bounds":{"x":240,"y":360,"width":70,"height":20},"pageIndex":0,"confidence":0.98}]
				""");
		RecordingRequester requester = new RecordingRequester(understandResponse("[]"), structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 18 }, "duplicate-display.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment ->
				assertThat(segment.flightNumber()).isEqualTo("BA117"));
		assertThat(result.needsReviewFields()).contains("spatial_duplicate_flight_number")
				.doesNotContain("duplicate_flight_number");
		assertExactlyTwoCalls(requester, "duplicate-display.png", "image/png", new byte[] { 18 });
	}

	@Test
	void spatialRoutingDoesNotReuseAirportEvidenceAcrossNonAdjacentSegments() throws Exception {
		JsonNode structure = spatialResponse("""
				[{"type":"paragraph","text":"8月4日","bounds":{"x":20,"y":20,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"BA100","bounds":{"x":240,"y":100,"width":70,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"LHR","bounds":{"x":50,"y":200,"width":50,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"BA200","bounds":{"x":240,"y":200,"width":70,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"JFK","bounds":{"x":400,"y":200,"width":50,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"BA300","bounds":{"x":240,"y":300,"width":70,"height":20},"pageIndex":0,"confidence":0.99}]
				""");
		RecordingRequester requester = new RecordingRequester(understandResponse("[]"), structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 19 }, "cross-route.png", "image/png");

		assertThat(result.segments()).extracting(segment -> segment.flightNumber())
				.containsExactly("BA100", "BA200", "BA300");
		assertThat(result.segments()).extracting(segment ->
				segment.departureAirport() + "-" + segment.arrivalAirport())
				.containsExactly("null-LHR", "LHR-JFK", "JFK-null");
		assertThat(result.needsReviewFields()).doesNotContain(
				"cross_segment_airport_conflict", "duplicate_flight_number",
				"spatial_duplicate_flight_number");
		assertExactlyTwoCalls(requester, "cross-route.png", "image/png", new byte[] { 19 });
	}

	@Test
	void incompleteUnderstandSegmentIsRetainedWhenStructureHasNoFlightAnchor() throws Exception {
		JsonNode understand = understandResponse("""
				[{"departure_date":"Aug 4","departure_airport":"LHR"}]
				""");
		RecordingRequester requester = new RecordingRequester(understand, spatialResponse("[]"));

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 8 }, "partial.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isNull();
			assertThat(segment.departureDate()).isEqualTo(LocalDate.of(2026, 8, 4));
			assertThat(segment.departureMonthDay()).isEqualTo("08-04");
			assertThat(segment.dateSource()).isEqualTo(
					com.selene.onestepflight.dto.DocumentFlightSegment.DateSource.CURRENT_YEAR_FROM_MONTH_DAY);
			assertThat(segment.departureAirport()).isEqualTo("LHR");
			assertThat(segment.arrivalAirport()).isNull();
		});
		assertThat(result.needsReviewFields()).contains("flight_number", "arrival_airport")
				.doesNotContain("departure_date");
		assertThat(DocumentResultClassifier.classify(result)).isEqualTo(DocumentResultClassification.PARTIAL);
		assertExactlyTwoCalls(requester, "partial.png", "image/png", new byte[] { 8 });
	}

	@Test
	void understandFailureStillReturnsStructureRecoveredSegments() throws Exception {
		RecordingRequester requester = new RecordingRequester(
				new RestClientException("understand unavailable PRIVATE-PASSENGER"),
				fixture("spatial-kl1036-mf812.json"));

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 9 }, "structure-only.png", "image/png");

		assertThat(result.segments()).extracting(segment -> segment.flightNumber())
				.containsExactly("KL1036", "MF812");
		assertThat(result.segments()).extracting(segment ->
				segment.departureAirport() + "-" + segment.arrivalAirport())
				.containsExactly("MAN-AMS", "AMS-XMN");
		assertThat(result.documentType().name()).isEqualTo("UNKNOWN");
		assertThat(mapper.writeValueAsString(result)).doesNotContain("PRIVATE-PASSENGER", "elements");
		assertExactlyTwoCalls(requester, "structure-only.png", "image/png", new byte[] { 9 });
	}

	@Test
	void structureFailureRetainsUnderstandResultAndMarksSpatialReviewUnavailable() throws Exception {
		JsonNode understand = understandResponse("""
				[{"flight_number":"BA117","departure_date":"2026-08-24",
				  "departure_airport":"LHR","arrival_airport":"JFK"}]
				""");
		RecordingRequester requester = new RecordingRequester(understand,
				new RestClientException("structure unavailable PRIVATE SECRET OCR PII"));
		Logger logger = (Logger) LoggerFactory.getLogger(NutrientBoardingPassDocumentProcessor.class);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);

		BoardingPassExtractionResponse result;
		try {
			result = processor(requester).extract(
					new byte[] { 10 }, "understand-only.pdf", "application/pdf");
		} finally {
			logger.detachAppender(logs);
			logs.stop();
		}

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("BA117");
			assertThat(segment.departureDate()).isEqualTo(LocalDate.of(2026, 8, 24));
			assertThat(segment.departureMonthDay()).isEqualTo("08-24");
			assertThat(segment.departureAirport()).isEqualTo("LHR");
			assertThat(segment.arrivalAirport()).isEqualTo("JFK");
		});
		assertThat(result.needsReviewFields()).contains("spatial_review_unavailable");
		assertThat(mapper.writeValueAsString(result)).doesNotContain("PRIVATE", "SECRET", "OCR", "PII");
		assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
				.allSatisfy(message -> assertThat(message)
						.doesNotContain("PRIVATE", "SECRET", "OCR", "PII"));
		assertExactlyTwoCalls(requester, "understand-only.pdf", "application/pdf", new byte[] { 10 });
	}

	@Test
	void bothProviderCallsFailAfterExactlyTwoAttempts() {
		RecordingRequester requester = new RecordingRequester(
				new RestClientException("understand unavailable"),
				new RestClientException("structure unavailable"));

		assertThatThrownBy(() -> processor(requester).extract(
				new byte[] { 11 }, "failed.png", "image/png"))
				.isInstanceOfSatisfying(ExternalServiceUnavailableException.class,
						exception -> assertThat(exception.code()).isEqualTo("NUTRIENT_EXTRACTION_FAILED"));
		assertExactlyTwoCalls(requester, "failed.png", "image/png", new byte[] { 11 });
	}

	@Test
	void spatialNoiseDoesNotCreateFlightsFromTimesTerminalsTicketsOrBaggage() throws Exception {
		JsonNode structure = spatialResponse("""
				[{"type":"paragraph","text":"8月4日","bounds":{"x":20,"y":10,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"LHR T2","bounds":{"x":100,"y":100,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"17:30 1x23KG 9999999999999","bounds":{"x":20,"y":120,"width":180,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"BA117","bounds":{"x":240,"y":150,"width":70,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"JFK","bounds":{"x":100,"y":230,"width":50,"height":20},"pageIndex":0,"confidence":0.99}]
				""");
		RecordingRequester requester = new RecordingRequester(
				understandResponse("[]"), structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 12 }, "noise.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("BA117");
			assertThat(segment.departureAirport()).isEqualTo("LHR");
			assertThat(segment.arrivalAirport()).isEqualTo("JFK");
			assertThat(segment.departureMonthDay()).isEqualTo("08-04");
		});
		assertExactlyTwoCalls(requester, "noise.png", "image/png", new byte[] { 12 });
	}

	@Test
	void airlineCodeInsideSensitiveUae123TokenIsNotParsedAsAnAirport() throws Exception {
		JsonNode structure = spatialResponse("""
				[{"type":"paragraph","text":"8月4日","bounds":{"x":20,"y":10,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"LHR","bounds":{"x":100,"y":100,"width":50,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"TICKET UAE123","bounds":{"x":100,"y":125,"width":130,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"BA117","bounds":{"x":240,"y":150,"width":70,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"JFK","bounds":{"x":100,"y":230,"width":50,"height":20},"pageIndex":0,"confidence":0.99}]
				""");
		RecordingRequester requester = new RecordingRequester(understandResponse("[]"), structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 24 }, "iata-boundary.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("BA117");
			assertThat(segment.departureAirport()).isEqualTo("LHR");
			assertThat(segment.arrivalAirport()).isEqualTo("JFK");
			assertThat(segment.departureAirport()).isNotEqualTo("UAE");
			assertThat(segment.arrivalAirport()).isNotEqualTo("UAE");
		});
		assertExactlyTwoCalls(requester, "iata-boundary.png", "image/png", new byte[] { 24 });
	}

	@Test
	void independentUnpositionedTransferAirportBlocksRemainDistinctAdjacentEvidence() throws Exception {
		JsonNode structure = spatialResponse("""
				[{"type":"paragraph","text":"8月4日","pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"MAN","pageIndex":0,"confidence":0.96},
				 {"type":"paragraph","text":"KL1036","pageIndex":0,"confidence":0.98},
				 {"type":"paragraph","text":"AMS","pageIndex":0,"confidence":0.94},
				 {"type":"paragraph","text":"AMS","pageIndex":0,"confidence":0.93},
				 {"type":"paragraph","text":"MF812","pageIndex":0,"confidence":0.98},
				 {"type":"paragraph","text":"XMN","pageIndex":0,"confidence":0.96}]
				""");
		RecordingRequester requester = new RecordingRequester(understandResponse("[]"), structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 25 }, "unpositioned-transfer.png", "image/png");

		assertThat(result.segments()).extracting(segment -> segment.flightNumber())
				.containsExactly("KL1036", "MF812");
		assertThat(result.segments()).extracting(segment ->
				segment.departureAirport() + "-" + segment.arrivalAirport())
				.containsExactly("MAN-AMS", "AMS-XMN");
		String firstArrivalSource = result.segments().get(0).fields()
				.get("arrival_airport").sourceLabel();
		String secondDepartureSource = result.segments().get(1).fields()
				.get("departure_airport").sourceLabel();
		assertThat(firstArrivalSource).isNotEqualTo(secondDepartureSource);
		assertThat(result.needsReviewFields()).doesNotContain("spatial_evidence_discarded");
		assertExactlyTwoCalls(requester, "unpositioned-transfer.png", "image/png", new byte[] { 25 });
	}

	@Test
	void spatialElementsMayOmitOptionalConfidence() throws Exception {
		JsonNode structure = spatialResponse("""
				[{"type":"paragraph","text":"8月4日","bounds":{"x":20,"y":10,"width":80,"height":20},"pageIndex":0},
				 {"type":"paragraph","text":"LHR","bounds":{"x":100,"y":100,"width":50,"height":20},"pageIndex":0},
				 {"type":"paragraph","text":"BA117","bounds":{"x":240,"y":150,"width":70,"height":20},"pageIndex":0},
				 {"type":"paragraph","text":"JFK","bounds":{"x":100,"y":230,"width":50,"height":20},"pageIndex":0}]
				""");
		RecordingRequester requester = new RecordingRequester(understandResponse("[]"), structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 13 }, "optional-confidence.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("BA117");
			assertThat(segment.departureAirport()).isEqualTo("LHR");
			assertThat(segment.arrivalAirport()).isEqualTo("JFK");
			assertThat(segment.departureMonthDay()).isEqualTo("08-04");
		});
		assertExactlyTwoCalls(requester, "optional-confidence.png", "image/png", new byte[] { 13 });
	}

	@Test
	void officialNestedKeyValueAndTableShapesPreserveSpatialEvidenceAndFormSegment() throws Exception {
		JsonNode structure = spatialResponse("""
				[{"type":"keyValueRegion","pageIndex":0,"confidence":0.40,
				  "pairs":[
				    {"key":{"text":"Date"},
				     "value":{"text":"8月4日","page":{"pageIndex":2},
				              "bounds":{"x":20,"y":20,"width":100,"height":20},"confidence":0.91}},
				    {"key":{"text":"Flight"},
				     "value":{"text":"BA117","page":{"pageIndex":2},
				              "bounds":{"x":240,"y":150,"width":70,"height":20},"confidence":0.97}}
				  ]},
				 {"type":"table","page":{"pageIndex":2},"confidence":0.50,
				  "cells":[
				    {"rowIndex":0,"columnIndex":0,"text":"LHR",
				     "bounds":{"x":100,"y":100,"width":50,"height":20},"confidence":0.92},
				    {"rowIndex":1,"columnIndex":0,"text":"JFK",
				     "bounds":{"x":100,"y":230,"width":50,"height":20},"confidence":0.93}
				  ]}]
				""");
		RecordingRequester requester = new RecordingRequester(understandResponse("[]"), structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 20 }, "official-spatial-shapes.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("BA117");
			assertThat(segment.departureMonthDay()).isEqualTo("08-04");
			assertThat(segment.departureAirport()).isEqualTo("LHR");
			assertThat(segment.arrivalAirport()).isEqualTo("JFK");
			assertThat(segment.page()).isEqualTo(2);
			assertThat(segment.boundingBox()).satisfies(box -> {
				assertThat(box.x()).isEqualTo(240.0d);
				assertThat(box.y()).isEqualTo(150.0d);
				assertThat(box.width()).isEqualTo(70.0d);
				assertThat(box.height()).isEqualTo(20.0d);
			});
			assertThat(segment.fields().get("flight_number")).satisfies(field -> {
				assertThat(field.value()).isEqualTo("BA117");
				assertThat(field.page()).isEqualTo(2);
				assertThat(field.confidence()).isEqualTo(0.97d);
				assertThat(field.boundingBox()).isEqualTo(segment.boundingBox());
				assertThat(field.sourceLabel()).startsWith("Nutrient Structure Spatial element ");
			});
			assertThat(segment.fields().get("arrival_airport")).satisfies(field -> {
				assertThat(field.page()).isEqualTo(2);
				assertThat(field.confidence()).isEqualTo(0.93d);
			});
		});
		assertExactlyTwoCalls(requester, "official-spatial-shapes.png", "image/png", new byte[] { 20 });
	}

	@Test
	void duplicateHeaderAnchorYieldsToDetailCardWithDenserLocalEvidence() throws Exception {
		JsonNode structure = spatialResponse("""
				[{"type":"paragraph","text":"8月4日","bounds":{"x":20,"y":10,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"BA117","bounds":{"x":240,"y":30,"width":70,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"details one","bounds":{"x":20,"y":50,"width":80,"height":10},"pageIndex":0},
				 {"type":"paragraph","text":"details two","bounds":{"x":20,"y":65,"width":80,"height":10},"pageIndex":0},
				 {"type":"paragraph","text":"details three","bounds":{"x":20,"y":80,"width":80,"height":10},"pageIndex":0},
				 {"type":"paragraph","text":"details four","bounds":{"x":20,"y":95,"width":80,"height":10},"pageIndex":0},
				 {"type":"paragraph","text":"details five","bounds":{"x":20,"y":110,"width":80,"height":10},"pageIndex":0},
				 {"type":"paragraph","text":"details six","bounds":{"x":20,"y":125,"width":80,"height":10},"pageIndex":0},
				 {"type":"paragraph","text":"details seven","bounds":{"x":20,"y":140,"width":80,"height":10},"pageIndex":0},
				 {"type":"paragraph","text":"details eight","bounds":{"x":20,"y":155,"width":80,"height":10},"pageIndex":0},
				 {"type":"paragraph","text":"details nine","bounds":{"x":20,"y":170,"width":80,"height":10},"pageIndex":0},
				 {"type":"paragraph","text":"details ten","bounds":{"x":20,"y":185,"width":80,"height":10},"pageIndex":0},
				 {"type":"paragraph","text":"LHR T2","bounds":{"x":100,"y":300,"width":80,"height":20},"pageIndex":0,"confidence":0.95},
				 {"type":"paragraph","text":"17:30","bounds":{"x":100,"y":325,"width":60,"height":20},"pageIndex":0,"confidence":0.95},
				 {"type":"paragraph","text":"BA117","bounds":{"x":240,"y":350,"width":70,"height":20},"pageIndex":0,"confidence":0.70},
				 {"type":"paragraph","text":"19:50","bounds":{"x":100,"y":375,"width":60,"height":20},"pageIndex":0,"confidence":0.95},
				 {"type":"paragraph","text":"JFK T4","bounds":{"x":100,"y":400,"width":80,"height":20},"pageIndex":0,"confidence":0.95}]
				""");
		RecordingRequester requester = new RecordingRequester(understandResponse("[]"), structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 22 }, "duplicate-header.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("BA117");
			assertThat(segment.departureAirport()).isEqualTo("LHR");
			assertThat(segment.arrivalAirport()).isEqualTo("JFK");
			assertThat(segment.scheduledDeparture()).isEqualTo("17:30");
			assertThat(segment.scheduledArrival()).isEqualTo("19:50");
			assertThat(segment.terminal()).isEqualTo("T2");
			assertThat(segment.fields().get("arrival_terminal").value()).isEqualTo("T4");
			assertThat(segment.boundingBox().y()).isEqualTo(350.0d);
			assertThat(segment.fields().get("flight_number").confidence()).isEqualTo(0.70d);
		});
		assertThat(result.needsReviewFields()).contains("spatial_duplicate_flight_number");
		assertExactlyTwoCalls(requester, "duplicate-header.png", "image/png", new byte[] { 22 });
	}

	@Test
	void characterOffsetsBindAirportsWithinOneBlockAndPageHeaderRouteDoesNotOverrideThem() throws Exception {
		JsonNode structure = spatialResponse("""
				[{"type":"paragraph","text":"8月4日","bounds":{"x":20,"y":10,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"MAN","bounds":{"x":20,"y":50,"width":50,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"XMN","bounds":{"x":500,"y":50,"width":50,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"LHR BA117 JFK","bounds":{"x":100,"y":200,"width":300,"height":100},"pageIndex":0,"confidence":0.98}]
				""");
		RecordingRequester requester = new RecordingRequester(understandResponse("[]"), structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 23 }, "single-block-route.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("BA117");
			assertThat(segment.departureAirport()).isEqualTo("LHR");
			assertThat(segment.arrivalAirport()).isEqualTo("JFK");
			assertThat(segment.departureAirport()).isNotEqualTo("MAN");
			assertThat(segment.arrivalAirport()).isNotEqualTo("XMN");
			assertThat(segment.fields().get("departure_airport").boundingBox())
					.isEqualTo(segment.boundingBox());
			assertThat(segment.fields().get("arrival_airport").boundingBox())
					.isEqualTo(segment.boundingBox());
		});
		assertExactlyTwoCalls(requester, "single-block-route.png", "image/png", new byte[] { 23 });
	}

	@Test
	void topLevelElementsAndUnknownNestedWrappersAreTraversedRecursively() throws Exception {
		JsonNode structure = mapper.readTree("""
				{"elements":[{"type":"unknown-container","children":[
				  {"kind":"heading","plainText":"Aug 4","pageNumber":1,
				   "rect":{"left":20,"top":20,"right":100,"bottom":40},"score":0.96},
				  {"type":"token","rawText":"LHR","pageNumber":1,
				   "bbox":[100,100,50,20],"confidence":{"score":0.94}},
				  {"type":"token","content":{"value":"BA117"},"pageNumber":1,
				   "bounds":{"left":240,"top":150,"right":310,"bottom":170},"score":0.98},
				  {"type":"token","text":"JFK","pageNumber":1,
				   "bounds":{"x":100,"y":230,"width":50,"height":20},"confidence":0.95}
				]}]}
				""");
		RecordingRequester requester = new RecordingRequester(understandResponse("[]"), structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 24 }, "top-level-elements.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("BA117");
			assertThat(segment.departureDate()).isEqualTo(LocalDate.of(2026, 8, 4));
			assertThat(segment.departureAirport()).isEqualTo("LHR");
			assertThat(segment.arrivalAirport()).isEqualTo("JFK");
			assertThat(segment.page()).isZero();
			assertThat(segment.fields().get("flight_number").confidence()).isEqualTo(0.98d);
		});
		JsonNode nestedWrapper = mapper.readTree("""
				{"result":{"data":{"unknownNodes":[
				 {"type":"token","text":"BA178","pageIndex":0,
				  "bounds":{"x":240,"y":150,"width":70,"height":20},"confidence":0.98}
				]}}}
				""");
		NutrientBoardingPassDocumentProcessor.SpatialPass nested =
				processor(requester).parseSpatial(nestedWrapper);
		assertThat(nested.candidates()).hasSize(1);
		assertThat(nested.blocks()).allSatisfy(block ->
				assertThat(block.sourcePath()).startsWith("$.result.data.unknownNodes"));
		assertExactlyTwoCalls(requester, "top-level-elements.png", "image/png", new byte[] { 24 });
	}

	@Test
	void adjacentSameLineFlightTokensJoinButCrossCardAndLargeGapTokensDoNot() throws Exception {
		JsonNode joined = spatialResponse("""
				[{"type":"heading","text":"8月4日","bounds":{"x":20,"y":20,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"token","text":"LHR","bounds":{"x":100,"y":150,"width":45,"height":20},"pageIndex":0,"confidence":0.96},
				 {"type":"token","text":"AF","bounds":{"x":230,"y":150,"width":26,"height":20},"pageIndex":0,"confidence":0.97},
				 {"type":"token","text":"1668","bounds":{"x":264,"y":150,"width":45,"height":20},"pageIndex":0,"confidence":0.96},
				 {"type":"token","text":"JFK","bounds":{"x":400,"y":150,"width":45,"height":20},"pageIndex":0,"confidence":0.96}]
				""");
		RecordingRequester joinedRequester = new RecordingRequester(understandResponse("[]"), joined);
		BoardingPassExtractionResponse joinedResult = processor(joinedRequester).extract(
				new byte[] { 25 }, "joined-flight.png", "image/png");

		assertThat(joinedResult.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("AF1668");
			assertThat(segment.flightNumberConfidence()).isEqualTo(
					com.selene.onestepflight.dto.DocumentFlightSegment.FlightNumberConfidence.HIGH);
			assertThat(segment.requiresFlightNumberConfirmation()).isFalse();
		});

		JsonNode separated = spatialResponse("""
				[{"type":"card","children":[{"type":"token","text":"AF","bounds":{"x":20,"y":100,"width":25,"height":20},"pageIndex":0,"confidence":0.99}]},
				 {"type":"card","children":[{"type":"token","text":"1668","bounds":{"x":52,"y":100,"width":45,"height":20},"pageIndex":0,"confidence":0.99}]},
				 {"type":"token","text":"MF","bounds":{"x":20,"y":300,"width":25,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"token","text":"812","bounds":{"x":600,"y":300,"width":40,"height":20},"pageIndex":0,"confidence":0.99}]
				""");
		RecordingRequester separatedRequester = new RecordingRequester(understandResponse("[]"), separated);
		BoardingPassExtractionResponse separatedResult = processor(separatedRequester).extract(
				new byte[] { 26 }, "separated-flight.png", "image/png");

		assertThat(separatedResult.segments()).isEmpty();
		assertExactlyTwoCalls(joinedRequester, "joined-flight.png", "image/png", new byte[] { 25 });
		assertExactlyTwoCalls(separatedRequester, "separated-flight.png", "image/png", new byte[] { 26 });
	}

	@Test
	void validDigitBearingIataPrefixCanBeHighConfidenceFromReliableSpatialTokens() throws Exception {
		JsonNode structure = spatialResponse("""
				[{"type":"token","text":"LHR","bounds":{"x":100,"y":150,"width":45,"height":20},"pageIndex":0,"confidence":0.97},
				 {"type":"token","text":"G9","bounds":{"x":230,"y":150,"width":26,"height":20},"pageIndex":0,"confidence":0.98},
				 {"type":"token","text":"123","bounds":{"x":264,"y":150,"width":40,"height":20},"pageIndex":0,"confidence":0.98},
				 {"type":"token","text":"JFK","bounds":{"x":400,"y":150,"width":45,"height":20},"pageIndex":0,"confidence":0.97}]
				""");
		RecordingRequester requester = new RecordingRequester(understandResponse("[]"), structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 27 }, "digit-iata.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("G9123");
			assertThat(segment.flightNumberConfidence()).isEqualTo(
					com.selene.onestepflight.dto.DocumentFlightSegment.FlightNumberConfidence.HIGH);
		});
	}

	@Test
	void strongerSpatialPrefixWithSameNumericSuffixResolvesUnderstandOcrCandidateGenerically() throws Exception {
		JsonNode understand = understandResponse("""
				[{"flight_number":"F1 668","departure_date":"9月5日",
				  "departure_airport":"CDG","arrival_airport":"MAN"}]
				""");
		JsonNode structure = spatialResponse("""
				[{"type":"heading","text":"9月5日","bounds":{"x":20,"y":20,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"token","text":"CDG","bounds":{"x":100,"y":150,"width":45,"height":20},"pageIndex":0,"confidence":0.97},
				 {"type":"token","text":"AF","bounds":{"x":230,"y":150,"width":26,"height":20},"pageIndex":0,"confidence":0.98},
				 {"type":"token","text":"1668","bounds":{"x":264,"y":150,"width":45,"height":20},"pageIndex":0,"confidence":0.98},
				 {"type":"token","text":"MAN","bounds":{"x":400,"y":150,"width":45,"height":20},"pageIndex":0,"confidence":0.97}]
				""");
		RecordingRequester requester = new RecordingRequester(understand, structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 28 }, "generic-ocr-correction.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("AF1668");
			assertThat(segment.flightNumberConfidence()).isEqualTo(
					com.selene.onestepflight.dto.DocumentFlightSegment.FlightNumberConfidence.HIGH);
			assertThat(segment.requiresFlightNumberConfirmation()).isFalse();
		});
		assertThat(result.needsReviewFields()).contains("conflict.flight_number_resolved")
				.doesNotContain("flight_number", "conflict.flight_number");
	}

	@Test
	void singleUnderstandChannelIsMediumConfidenceAndUnsupportedPrefixIsMissing() throws Exception {
		RecordingRequester mediumRequester = new RecordingRequester(understandResponse("""
				[{"flight_number":"BA117","departure_date":"2026-08-04"}]
				"""), spatialResponse("[]"));
		BoardingPassExtractionResponse medium = processor(mediumRequester).extract(
				new byte[] { 29 }, "medium.png", "image/png");

		assertThat(medium.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("BA117");
			assertThat(segment.flightNumberConfidence()).isEqualTo(
					com.selene.onestepflight.dto.DocumentFlightSegment.FlightNumberConfidence.MEDIUM);
			assertThat(segment.requiresFlightNumberConfirmation()).isTrue();
		});
		assertThat(DocumentResultClassifier.classify(medium)).isEqualTo(DocumentResultClassification.PARTIAL);

		RecordingRequester lowRequester = new RecordingRequester(understandResponse("""
				[{"flight_number":"Q0999","departure_date":"2026-08-04"}]
				"""), spatialResponse("[]"));
		BoardingPassExtractionResponse low = processor(lowRequester).extract(
				new byte[] { 30 }, "low.png", "image/png");
		assertThat(low.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isNull();
			assertThat(segment.flightNumberConfidence()).isEqualTo(
					com.selene.onestepflight.dto.DocumentFlightSegment.FlightNumberConfidence.LOW);
		});
	}

	@Test
	void localDateHeadingsStopAtNewHeadingAndArrivalDateNeverPropagatesForward() throws Exception {
		JsonNode structure = spatialResponse("""
				[{"type":"heading","text":"8月4日","bounds":{"x":20,"y":20,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"token","text":"LHR","bounds":{"x":100,"y":100,"width":45,"height":20},"pageIndex":0,"confidence":0.97},
				 {"type":"token","text":"BA117","bounds":{"x":240,"y":150,"width":70,"height":20},"pageIndex":0,"confidence":0.98},
				 {"type":"token","text":"JFK","bounds":{"x":100,"y":200,"width":45,"height":20},"pageIndex":0,"confidence":0.97},
				 {"type":"paragraph","text":"到达 8月5日","bounds":{"x":20,"y":240,"width":100,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"heading","text":"9月5日","bounds":{"x":20,"y":300,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"token","text":"JFK","bounds":{"x":100,"y":350,"width":45,"height":20},"pageIndex":0,"confidence":0.97},
				 {"type":"token","text":"BA178","bounds":{"x":240,"y":400,"width":70,"height":20},"pageIndex":0,"confidence":0.98},
				 {"type":"token","text":"LHR","bounds":{"x":100,"y":450,"width":45,"height":20},"pageIndex":0,"confidence":0.97}]
				""");
		RecordingRequester requester = new RecordingRequester(understandResponse("[]"), structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 31 }, "date-boundaries.png", "image/png");

		assertThat(result.segments()).extracting(segment -> segment.departureDate())
				.containsExactly(LocalDate.of(2026, 8, 4), LocalDate.of(2026, 9, 5));
	}

	@Test
	void dateInsideOneCardDoesNotCrossIntoAnotherCard() throws Exception {
		JsonNode structure = spatialResponse("""
				[{"type":"card","children":[
				  {"type":"heading","text":"8月4日","bounds":{"x":20,"y":20,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				  {"type":"token","text":"LHR","bounds":{"x":100,"y":100,"width":45,"height":20},"pageIndex":0,"confidence":0.97},
				  {"type":"token","text":"BA117","bounds":{"x":240,"y":150,"width":70,"height":20},"pageIndex":0,"confidence":0.98},
				  {"type":"token","text":"JFK","bounds":{"x":100,"y":200,"width":45,"height":20},"pageIndex":0,"confidence":0.97}]},
				 {"type":"card","children":[
				  {"type":"token","text":"JFK","bounds":{"x":100,"y":350,"width":45,"height":20},"pageIndex":0,"confidence":0.97},
				  {"type":"token","text":"BA178","bounds":{"x":240,"y":400,"width":70,"height":20},"pageIndex":0,"confidence":0.98},
				  {"type":"token","text":"LHR","bounds":{"x":100,"y":450,"width":45,"height":20},"pageIndex":0,"confidence":0.97}]}]
				""");
		RecordingRequester requester = new RecordingRequester(understandResponse("[]"), structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 32 }, "card-date-boundary.png", "image/png");

		assertThat(result.segments()).extracting(segment -> segment.departureDate())
				.containsExactly(LocalDate.of(2026, 8, 4), null);
	}

	@Test
	void explicitYearWinsOverCurrentYearCompletionWhenMonthAndDayAgree() throws Exception {
		JsonNode understand = understandResponse("""
				[{"flight_number":"BA117","departure_date":"2025-08-04",
				  "departure_airport":"LHR","arrival_airport":"JFK"}]
				""");
		RecordingRequester requester = new RecordingRequester(understand,
				singleFlightSpatial("8月4日", "T4"));

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 33 }, "explicit-year.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.departureDate()).isEqualTo(LocalDate.of(2025, 8, 4));
			assertThat(segment.dateSource()).isEqualTo(
					com.selene.onestepflight.dto.DocumentFlightSegment.DateSource.EXPLICIT_FULL_DATE);
		});
		assertThat(result.needsReviewFields()).doesNotContain("conflict.departure_date");
	}

	@Test
	void repeatedFlightNumberOnDifferentCardsDatesAndPagesRemainsDistinct() throws Exception {
		JsonNode structure = spatialResponse("""
				[{"type":"card","children":[
				  {"type":"heading","text":"8月4日","bounds":{"x":20,"y":20,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				  {"type":"token","text":"LHR","bounds":{"x":100,"y":100,"width":45,"height":20},"pageIndex":0,"confidence":0.97},
				  {"type":"token","text":"BA117","bounds":{"x":240,"y":150,"width":70,"height":20},"pageIndex":0,"confidence":0.98},
				  {"type":"token","text":"JFK","bounds":{"x":100,"y":200,"width":45,"height":20},"pageIndex":0,"confidence":0.97}]},
				 {"type":"card","children":[
				  {"type":"heading","text":"9月5日","bounds":{"x":20,"y":300,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				  {"type":"token","text":"JFK","bounds":{"x":100,"y":350,"width":45,"height":20},"pageIndex":0,"confidence":0.97},
				  {"type":"token","text":"BA117","bounds":{"x":240,"y":400,"width":70,"height":20},"pageIndex":0,"confidence":0.98},
				  {"type":"token","text":"LHR","bounds":{"x":100,"y":450,"width":45,"height":20},"pageIndex":0,"confidence":0.97}]},
				 {"type":"card","children":[
				  {"type":"heading","text":"10月1日","bounds":{"x":20,"y":20,"width":80,"height":20},"pageIndex":1,"confidence":0.99},
				  {"type":"token","text":"LHR","bounds":{"x":100,"y":100,"width":45,"height":20},"pageIndex":1,"confidence":0.97},
				  {"type":"token","text":"BA117","bounds":{"x":240,"y":150,"width":70,"height":20},"pageIndex":1,"confidence":0.98},
				  {"type":"token","text":"CDG","bounds":{"x":100,"y":200,"width":45,"height":20},"pageIndex":1,"confidence":0.97}]}]
				""");
		RecordingRequester requester = new RecordingRequester(understandResponse("[]"), structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 34 }, "repeated-flight.png", "image/png");

		assertThat(result.segments()).hasSize(3);
		assertThat(result.segments()).extracting(segment -> segment.flightNumber())
				.containsExactly("BA117", "BA117", "BA117");
		assertThat(result.segments()).extracting(segment -> segment.departureDate())
				.containsExactly(LocalDate.of(2026, 8, 4), LocalDate.of(2026, 9, 5),
						LocalDate.of(2026, 10, 1));
		assertThat(result.segments()).extracting(segment -> segment.page())
				.containsExactly(0, 0, 1);
		assertThat(result.needsReviewFields()).doesNotContain(
				"duplicate_flight_number", "spatial_duplicate_flight_number");
	}

	@Test
	void ambiguousRegistryPrefixDoesNotBecomeHighConfidenceFromChannelAgreement() throws Exception {
		JsonNode understand = understandResponse("""
				[{"flight_number":"1I123","departure_date":"8月4日",
				  "departure_airport":"LHR","arrival_airport":"JFK"}]
				""");
		JsonNode structure = spatialResponse("""
				[{"type":"heading","text":"8月4日","bounds":{"x":20,"y":20,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"token","text":"LHR","bounds":{"x":100,"y":100,"width":45,"height":20},"pageIndex":0,"confidence":0.97},
				 {"type":"token","text":"1I123","bounds":{"x":240,"y":150,"width":70,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"token","text":"JFK","bounds":{"x":100,"y":200,"width":45,"height":20},"pageIndex":0,"confidence":0.97}]
				""");
		RecordingRequester requester = new RecordingRequester(understand, structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 35 }, "ambiguous-prefix.png", "image/png");

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("1I123");
			assertThat(segment.flightNumberConfidence()).isEqualTo(
					com.selene.onestepflight.dto.DocumentFlightSegment.FlightNumberConfidence.MEDIUM);
			assertThat(segment.requiresFlightNumberConfirmation()).isTrue();
		});
		assertThat(DocumentResultClassifier.classify(result)).isEqualTo(DocumentResultClassification.PARTIAL);
	}

	@Test
	void adjacentFlightFragmentsInsideSensitiveLocalContextAreNeverJoined() throws Exception {
		JsonNode structure = spatialResponse("""
				[{"type":"token","text":"PNR 票号","bounds":{"x":20,"y":150,"width":90,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"token","text":"AF","bounds":{"x":140,"y":150,"width":26,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"token","text":"1668","bounds":{"x":174,"y":150,"width":45,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"token","text":"LHR","bounds":{"x":260,"y":150,"width":45,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"token","text":"JFK","bounds":{"x":360,"y":150,"width":45,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"token","text":"PNR","bounds":{"x":140,"y":230,"width":34,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"token","text":"1234","bounds":{"x":182,"y":230,"width":45,"height":20},"pageIndex":0,"confidence":0.99}]
				""");
		RecordingRequester requester = new RecordingRequester(understandResponse("[]"), structure);

		BoardingPassExtractionResponse result = processor(requester).extract(
				new byte[] { 36 }, "sensitive-fragments.png", "image/png");

		assertThat(result.segments()).isEmpty();
		assertExactlyTwoCalls(requester, "sensitive-fragments.png", "image/png", new byte[] { 36 });
	}

	private NutrientBoardingPassDocumentProcessor processor(RecordingRequester requester) {
		return new NutrientBoardingPassDocumentProcessor(mapper, CLOCK, requester);
	}

	private JsonNode fixture(String name) throws Exception {
		String json = new ClassPathResource("fixtures/nutrient/" + name)
				.getContentAsString(StandardCharsets.UTF_8);
		return mapper.readTree(json);
	}

	private JsonNode understandResponse(String segments) throws Exception {
		return mapper.readTree("""
				{"output":{"data":{"document_type":"ITINERARY","segments":%s},
				"metadata":{"document_type":{"confidence":0.99},"segments":[]}}}
				""".formatted(segments));
	}

	private JsonNode spatialResponse(String elements) throws Exception {
		return mapper.readTree("""
				{"output":{"elements":%s}}
				""".formatted(elements));
	}

	private JsonNode singleFlightSpatial(String date, String arrivalTerminal) throws Exception {
		return spatialResponse("""
				[{"type":"paragraph","text":"%s","bounds":{"x":20,"y":20,"width":100,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"LHR T2","bounds":{"x":100,"y":100,"width":80,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"BA117","bounds":{"x":240,"y":150,"width":70,"height":20},"pageIndex":0,"confidence":0.99},
				 {"type":"paragraph","text":"JFK %s","bounds":{"x":100,"y":230,"width":80,"height":20},"pageIndex":0,"confidence":0.99}]
				""".formatted(date, arrivalTerminal));
	}

	private void assertExactlyTwoCalls(RecordingRequester requester, String filename,
			String contentType, byte[] expectedDocument) {
		assertThat(requester.stages).containsExactly("understand", "structure");
		assertThat(requester.instructions).hasSize(2);
		assertThat(requester.instructions).extracting(instructions -> instructions.get("mode"))
				.containsExactly("understand", "structure");
		assertThat(requester.filenames).containsOnly(filename);
		assertThat(requester.contentTypes).containsOnly(contentType);
		assertThat(requester.documents).allSatisfy(document ->
				assertThat(document).containsExactly(expectedDocument));
	}

	private static final class RecordingRequester
			implements NutrientBoardingPassDocumentProcessor.ExtractionRequester {
		private final Map<String, Object> outcomes = new LinkedHashMap<>();
		private final List<String> stages = new ArrayList<>();
		private final List<Map<String, Object>> instructions = new ArrayList<>();
		private final List<byte[]> documents = new ArrayList<>();
		private final List<String> filenames = new ArrayList<>();
		private final List<String> contentTypes = new ArrayList<>();

		private RecordingRequester(Object understand, Object structure) {
			outcomes.put("understand", understand);
			outcomes.put("structure", structure);
		}

		@Override
		public JsonNode request(byte[] documentBytes, String filename, String contentType,
				String stage, Map<String, Object> instructions) {
			stages.add(stage);
			this.instructions.add(instructions);
			documents.add(documentBytes.clone());
			filenames.add(filename);
			contentTypes.add(contentType);
			Object outcome = outcomes.get(stage);
			if (outcome instanceof RuntimeException exception) throw exception;
			if (outcome instanceof JsonNode node) return node;
			throw new AssertionError("Unexpected Nutrient request stage: " + stage);
		}
	}
}
