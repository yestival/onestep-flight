package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;
import com.selene.onestepflight.dto.DocumentFlightSegment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DocumentExtractionPipelineTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final NutrientBoardingPassDocumentProcessor processor =
			new NutrientBoardingPassDocumentProcessor(mapper);

	@Test
	void everyUnderstandSegmentProducesExactlyOneFinalSegmentWithoutLocalFilteringOrMerging()
			throws Exception {
		JsonNode understand = understandResponse("""
				[{"flight_number":"ZX101"},
				 {"departure_date":"2032-06-12"},
				 {"departure_airport":"QQQ","arrival_airport":"ZZZ",
				  "scheduled_departure":"2032-06-13T07:15"},
				 {}]
				""");

		BoardingPassExtractionResponse result = processor.toResponse(understand);

		assertThat(result.segments()).hasSize(4);
		assertThat(result.segments()).extracting(DocumentFlightSegment::flightNumber)
				.containsExactly("ZX101", null, null, null);
		assertThat(result.segments()).extracting(DocumentFlightSegment::departureDate)
				.containsExactly(null, LocalDate.of(2032, 6, 12), null, null);
		assertThat(result.segments()).extracting(DocumentFlightSegment::departureAirport)
				.containsExactly(null, null, "QQQ", null);
		assertThat(result.segments()).extracting(DocumentFlightSegment::arrivalAirport)
				.containsExactly(null, null, "ZZZ", null);
		assertThat(result.segments()).extracting(DocumentFlightSegment::scheduledDeparture)
				.containsExactly(null, null, "2032-06-13T07:15", null);
	}

	@Test
	void multiLegCoreFieldsStayTogetherInUnderstandOrderWithoutDuplicationOrCrossBinding()
			throws Exception {
		JsonNode understand = understandResponse("""
				[{"flight_number":"ZX111","departure_date":"2032-07-01",
				  "departure_airport":"QQQ","arrival_airport":"ZZZ",
				  "scheduled_departure":"2032-07-01T06:10",
				  "scheduled_arrival":"2032-07-01T08:20","boarding_time":"05:35"},
				 {"flight_number":"QY222","departure_date":"2032-07-02",
				  "departure_airport":"XXX","arrival_airport":"YYY",
				  "scheduled_departure":"2032-07-02T11:25",
				  "scheduled_arrival":"2032-07-02T14:40","boarding_time":"10:50"},
				 {"flight_number":"WV333","departure_date":"2032-07-03",
				  "departure_airport":"VVV","arrival_airport":"WWW",
				  "scheduled_departure":"2032-07-03T16:05",
				  "scheduled_arrival":"2032-07-03T19:30","boarding_time":"15:20"}]
				""");

		BoardingPassExtractionResponse result = processor.toResponse(understand);

		assertThat(result.segments()).hasSize(3);
		assertThat(result.segments()).extracting(
				DocumentFlightSegment::flightNumber,
				DocumentFlightSegment::departureDate,
				DocumentFlightSegment::departureAirport,
				DocumentFlightSegment::arrivalAirport,
				DocumentFlightSegment::scheduledDeparture,
				DocumentFlightSegment::scheduledArrival,
				DocumentFlightSegment::boardingTime)
				.containsExactly(
						tuple("ZX111", LocalDate.of(2032, 7, 1), "QQQ", "ZZZ",
								"2032-07-01T06:10", "2032-07-01T08:20", "05:35"),
						tuple("QY222", LocalDate.of(2032, 7, 2), "XXX", "YYY",
								"2032-07-02T11:25", "2032-07-02T14:40", "10:50"),
						tuple("WV333", LocalDate.of(2032, 7, 3), "VVV", "WWW",
								"2032-07-03T16:05", "2032-07-03T19:30", "15:20"))
				.doesNotHaveDuplicates();
	}

	@Test
	void completeNonEmptyUnderstandFieldsArePreservedWithoutLocalRewriting() throws Exception {
		JsonNode understand = understandResponse("""
				[{"flight_number":"ZX901","departure_date":"2033-04-05",
				  "departure_airport":"QQQ","arrival_airport":"ZZZ",
				  "scheduled_departure":"2033-04-05T06:07",
				  "scheduled_arrival":"2033-04-05T08:09","boarding_time":"05:37",
				  "terminal":"North-2","gate":"G-14","seat":"18C"}]
				""");

		BoardingPassExtractionResponse result = processor.toResponse(understand);

		assertThat(result.segments()).singleElement().satisfies(segment -> {
			assertThat(segment.flightNumber()).isEqualTo("ZX901");
			assertThat(segment.departureDate()).isEqualTo(LocalDate.of(2033, 4, 5));
			assertThat(segment.departureAirport()).isEqualTo("QQQ");
			assertThat(segment.arrivalAirport()).isEqualTo("ZZZ");
			assertThat(segment.scheduledDeparture()).isEqualTo("2033-04-05T06:07");
			assertThat(segment.scheduledArrival()).isEqualTo("2033-04-05T08:09");
			assertThat(segment.boardingTime()).isEqualTo("05:37");
			assertThat(segment.terminal()).isEqualTo("North-2");
			assertThat(segment.gate()).isEqualTo("G-14");
			assertThat(segment.seat()).isEqualTo("18C");

			Map<String, String> expectedEvidence = Map.of(
					"flight_number", "ZX901",
					"departure_date", "2033-04-05",
					"departure_airport", "QQQ",
					"arrival_airport", "ZZZ",
					"scheduled_departure", "2033-04-05T06:07",
					"scheduled_arrival", "2033-04-05T08:09",
					"boarding_time", "05:37",
					"terminal", "North-2",
					"gate", "G-14",
					"seat", "18C");
			assertThat(segment.fields()).containsOnlyKeys(expectedEvidence.keySet());
			assertThat(segment.fields()).allSatisfy((name, field) ->
					assertThat(field.value()).isEqualTo(expectedEvidence.get(name)));
		});
	}

	private JsonNode understandResponse(String segments) throws Exception {
		return mapper.readTree("""
				{"output":{"data":{"document_type":"ITINERARY","segments":%s},
				"metadata":{"document_type":{"confidence":0.99},"segments":[]}}}
				""".formatted(segments));
	}
}
