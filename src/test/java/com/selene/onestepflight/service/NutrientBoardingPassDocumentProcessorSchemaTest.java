package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
				"name", "passenger_name", "pnr", "booking_reference", "ticket_number", "passport", "barcode");
		assertThat(schema.get("description").toString())
				.contains("same order as the document", "shared by a heading", "never carry it across");
		assertThat(((Map<String, Object>) segmentProperties.get("departure_date")).get("description").toString())
				.contains("without inventing a year", "M月D日", "MM/DD", "Aug 4", "4 Aug");
		assertThat(((Map<String, Object>) segmentProperties.get("scheduled_arrival")).get("description").toString())
				.contains("next day or +1", "never change departure_date");
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
	void flightNumberWithTrailingLetterUsesTheSameFormatAsLiveLookup() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		NutrientBoardingPassDocumentProcessor processor =
				new NutrientBoardingPassDocumentProcessor(mapper);
		String providerResponse = """
				{"output":{"data":{"document_type":"ITINERARY","segments":[{
				"flight_number":"BA 117A","departure_date":"2026-08-24",
				"departure_airport":"LHR","arrival_airport":"JFK"}]},"metadata":{
				"document_type":{"confidence":0.99},"segments":[{
				"flight_number":{"confidence":0.99},"departure_date":{"confidence":0.99},
				"departure_airport":{"confidence":0.99},"arrival_airport":{"confidence":0.99}}]}}}
				""";

		var response = processor.toResponse(mapper.readTree(providerResponse));

		assertThat(response.flightNumber()).isEqualTo("BA117A");
		assertThat(new FlightService(null, null).normalize(response.flightNumber())).isEqualTo("BA117A");
	}

	@Test
	void multiSegmentItineraryKeepsEveryLegWithoutInventingATopLevelIdentity() throws Exception {
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
		assertThat(response.flightNumber()).as("the resolver chooses a lookup leg from the request identity").isNull();
		assertThat(response.departureDate()).isNull();
		assertThat(response.requiresDocumentConfirmation()).isFalse();
	}

	@Test
	void parsesExplicitAndYearlessDatesAgainstTheInjectedUtcYearAndKeepsPrintedEvidence() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		Instant now = Instant.parse("2026-12-31T23:30:00Z");
		NutrientBoardingPassDocumentProcessor processor = new NutrientBoardingPassDocumentProcessor(
				mapper, Clock.fixed(now, ZoneOffset.UTC));
		String providerResponse = """
				{"output":{"data":{"document_type":"ITINERARY","segments":[
				{"flight_number":"BA101","departure_date":"2027-01-02"},
				{"flight_number":"BA102","departure_date":"2027年1月3日"},
				{"flight_number":"BA103","departure_date":"8月4日"},
				{"flight_number":"BA104","departure_date":"08/05"},
				{"flight_number":"BA105","departure_date":"8/6"},
				{"flight_number":"BA106","departure_date":"Aug 7"},
				{"flight_number":"BA107","departure_date":"8 Aug"},
				{"flight_number":"BA108","departure_date":"summer"}
				]},"metadata":{"document_type":{"confidence":0.99}}}}
				""";

		var response = processor.toResponse(mapper.readTree(providerResponse));

		assertThat(response.segments()).extracting(segment -> segment.flightNumber())
				.containsExactly("BA101", "BA102", "BA103", "BA104", "BA105", "BA106", "BA107", "BA108");
		assertThat(response.segments()).extracting(segment -> segment.departureDate())
				.containsExactly(
						LocalDate.of(2027, 1, 2), LocalDate.of(2027, 1, 3),
						LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 5),
						LocalDate.of(2026, 8, 6), LocalDate.of(2026, 8, 7),
						LocalDate.of(2026, 8, 8), null);
		assertThat(response.segments().get(2).fields().get("departure_date").value()).isEqualTo("8月4日");
		assertThat(response.segments().get(5).fields().get("departure_date").value()).isEqualTo("Aug 7");
		assertThat(response.segments().get(7).fields().get("departure_date").value()).isNull();
		assertThat(response.needsReviewFields()).doesNotContain(
				"segments[2].departure_date", "segments[3].departure_date", "segments[4].departure_date",
				"segments[5].departure_date", "segments[6].departure_date");
		assertThat(response.extractedAt()).isEqualTo(now);
	}

	@Test
	void keepsCompleteAndPartialSegmentsInTheirOriginalOrder() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		NutrientBoardingPassDocumentProcessor processor = new NutrientBoardingPassDocumentProcessor(
				mapper, Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
		String providerResponse = """
				{"output":{"data":{"document_type":"ITINERARY","segments":[
				{"flight_number":"BA117","departure_date":"2026-08-24","departure_airport":"LHR","arrival_airport":"JFK"},
				{"flight_number":"BA178","departure_airport":"JFK","arrival_airport":"LHR"},
				{"departure_date":"8/30","departure_airport":"LHR","arrival_airport":"CDG"}
				]},"metadata":{"document_type":{"confidence":0.99},"segments":[
				{"flight_number":{"confidence":0.99},"departure_date":{"confidence":0.99}},
				{"flight_number":{"confidence":0.99}},
				{"departure_date":{"confidence":0.99}}
				]}}}
				""";

		var response = processor.toResponse(mapper.readTree(providerResponse));

		assertThat(response.segments()).hasSize(3);
		assertThat(response.segments()).extracting(segment -> segment.flightNumber())
				.containsExactly("BA117", "BA178", null);
		assertThat(response.segments()).extracting(segment -> segment.departureDate())
				.containsExactly(LocalDate.of(2026, 8, 24), null, LocalDate.of(2026, 8, 30));
		assertThat(response.needsReviewFields()).contains("segments[1].departure_date", "segments[2].flight_number");
		assertThat(DocumentResultClassifier.classify(response)).isEqualTo(DocumentResultClassification.USABLE);
	}

	@Test
	void preservesAVisibleSharedHeadingDateOnEachGovernedSegment() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		NutrientBoardingPassDocumentProcessor processor = new NutrientBoardingPassDocumentProcessor(
				mapper, Clock.fixed(Instant.parse("2028-02-01T00:00:00Z"), ZoneOffset.UTC));
		String providerResponse = """
				{"output":{"data":{"document_type":"ITINERARY","segments":[
				{"flight_number":"BA201","departure_date":"2月14日","departure_airport":"LHR","arrival_airport":"CDG"},
				{"flight_number":"BA202","departure_date":"2月14日","departure_airport":"CDG","arrival_airport":"FCO"}
				]},"metadata":{"document_type":{"confidence":0.99}}}}
				""";

		var response = processor.toResponse(mapper.readTree(providerResponse));

		assertThat(response.segments()).extracting(segment -> segment.flightNumber())
				.containsExactly("BA201", "BA202");
		assertThat(response.segments()).extracting(segment -> segment.departureDate())
				.containsExactly(LocalDate.of(2028, 2, 14), LocalDate.of(2028, 2, 14));
		assertThat(response.segments()).allSatisfy(segment ->
				assertThat(segment.fields().get("departure_date").value()).isEqualTo("2月14日"));
	}

	@Test
	void preservesAnExplicitNextDayArrivalWithoutChangingDepartureDate() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		NutrientBoardingPassDocumentProcessor processor = new NutrientBoardingPassDocumentProcessor(mapper);
		String providerResponse = """
				{"output":{"data":{"document_type":"ITINERARY","segments":[{
				"flight_number":"BA203","departure_date":"2026-08-24",
				"departure_airport":"LHR","arrival_airport":"JFK",
				"scheduled_departure":"2026-08-24T23:30","scheduled_arrival":"2026-08-25T02:10"
				}]},"metadata":{"document_type":{"confidence":0.99}}}}
				""";

		var segment = processor.toResponse(mapper.readTree(providerResponse)).segments().get(0);

		assertThat(segment.departureDate()).isEqualTo(LocalDate.of(2026, 8, 24));
		assertThat(segment.scheduledDeparture()).isEqualTo("2026-08-24T23:30");
		assertThat(segment.scheduledArrival()).isEqualTo("2026-08-25T02:10");
	}

	@Test
	void fixedMultiSegmentAcceptanceSamplesKeepEveryLegInDocumentOrder() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		NutrientBoardingPassDocumentProcessor processor =
				new NutrientBoardingPassDocumentProcessor(mapper);
		String firstSample = """
				{"output":{"data":{"document_type":"ITINERARY","segments":[
				{"flight_number":"MF825","departure_date":"2026-08-04","departure_airport":"XMN","arrival_airport":"CDG"},
				{"flight_number":"AF1668","departure_date":"2026-08-05","departure_airport":"CDG","arrival_airport":"MAN"}
				]},"metadata":{"document_type":{"confidence":0.99}}}}
				""";
		String secondSample = """
				{"output":{"data":{"document_type":"ITINERARY","segments":[
				{"flight_number":"KL1036","departure_date":"2026-08-10","departure_airport":"MAN","arrival_airport":"AMS","scheduled_departure":"2026-08-10T08:00","scheduled_arrival":"2026-08-10T10:20"},
				{"flight_number":"MF812","departure_date":"2026-08-10","departure_airport":"AMS","arrival_airport":"XMN","scheduled_departure":"2026-08-10T18:30","scheduled_arrival":"2026-08-11T12:20"}
				]},"metadata":{"document_type":{"confidence":0.99}}}}
				""";

		for (int attempt = 0; attempt < 20; attempt++) {
			var first = processor.toResponse(mapper.readTree(firstSample));
			var second = processor.toResponse(mapper.readTree(secondSample));

			assertThat(first.segments()).hasSize(2).extracting(segment -> segment.flightNumber() + " "
					+ segment.departureAirport() + "→" + segment.arrivalAirport())
					.containsExactly("MF825 XMN→CDG", "AF1668 CDG→MAN");
			assertThat(first.segments()).extracting(segment -> segment.departureDate())
					.containsExactly(LocalDate.of(2026, 8, 4), LocalDate.of(2026, 8, 5));
			assertThat(first.segments()).extracting(segment -> segment.fields().get("departure_date").value())
					.containsExactly("2026-08-04", "2026-08-05");
			assertThat(second.segments()).hasSize(2).extracting(segment -> segment.flightNumber() + " "
					+ segment.departureAirport() + "→" + segment.arrivalAirport())
					.containsExactly("KL1036 MAN→AMS", "MF812 AMS→XMN");
			assertThat(second.segments()).extracting(segment -> segment.departureDate())
					.containsExactly(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 10));
			assertThat(second.segments()).extracting(segment -> segment.scheduledDeparture())
					.containsExactly("2026-08-10T08:00", "2026-08-10T18:30");
			assertThat(second.segments()).extracting(segment -> segment.scheduledArrival())
					.containsExactly("2026-08-10T10:20", "2026-08-11T12:20");
			assertThat(second.segments().get(1).departureDate()).isEqualTo(LocalDate.of(2026, 8, 10));
			assertThat(second.segments().get(1).scheduledArrival()).isEqualTo("2026-08-11T12:20");
		}
	}

	@Test
	void sensitiveProviderPropertiesNeverCrossThePublicResponseBoundary() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		NutrientBoardingPassDocumentProcessor processor = new NutrientBoardingPassDocumentProcessor(mapper);
		String providerResponse = """
				{"output":{"data":{"document_type":"BOARDING_PASS","segments":[{
				"flight_number":"BA117","departure_date":"2026-08-24","departure_airport":"LHR","arrival_airport":"JFK",
				"name":"SECRET-NAME","pnr":"SECRET-PNR","booking_reference":"SECRET-BOOKING",
				"ticket_number":"SECRET-TICKET","passport":"SECRET-PASSPORT","barcode":"SECRET-BARCODE"
				}]},"metadata":{"document_type":{"confidence":0.99}}}}
				""";

		var response = processor.toResponse(mapper.readTree(providerResponse));
		String publicJson = mapper.writeValueAsString(response);

		assertThat(response.segments().get(0).fields()).containsOnlyKeys(
				"flight_number", "departure_date", "departure_airport", "arrival_airport",
				"scheduled_departure", "scheduled_arrival", "boarding_time", "terminal", "gate", "seat");
		assertThat(publicJson).doesNotContain("SECRET-NAME", "SECRET-PNR", "SECRET-BOOKING",
				"SECRET-TICKET", "SECRET-PASSPORT", "SECRET-BARCODE");
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
