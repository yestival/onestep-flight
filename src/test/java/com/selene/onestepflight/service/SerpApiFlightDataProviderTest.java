package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.selene.onestepflight.dto.ProviderFlightResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SerpApiFlightDataProviderTest {

	private static final LocalDate DATE = LocalDate.of(2026, 8, 25);
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void usesFlightResultEndpointAndDoesNotHardFilterAConflictingRouteHint() {
		Fixture fixture = fixture();
		fixture.server().expect(request -> {
			assertThat(request.getMethod()).isEqualTo(HttpMethod.GET);
			assertThat(request.getURI().getPath()).isEqualTo("/search");
			var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
			assertThat(query.getFirst("engine")).isEqualTo("google");
			assertThat(decoded(query.getFirst("q"))).isEqualTo("Flight BA117");
			assertThat(query.getFirst("hl")).isEqualTo("en");
			assertThat(query.getFirst("api_key")).isEqualTo("stub-key");
		}).andRespond(withSuccess("""
				{
				  "search_metadata":{"status":"Success"},
				  "flight_result":{
				    "flight_designator":"BA 117",
				    "dates":[{
				      "date":"2026-08-25",
				      "metadata":{"origin":"CDG","destination":"XMN","status":"SCHEDULED_STATUS"},
				      "departure_airport":{"id":"CDG","city":"Paris","scheduled_time":"2026-08-25T10:00:00+02:00"},
				      "arrival_airport":{"id":"XMN","city":"Xiamen","scheduled_time":"2026-08-25T22:00:00+08:00"}
				    }]
				  }
				}
				""", MediaType.APPLICATION_JSON));

		ProviderFlightResult result = fixture.provider().findFlight("BA117", DATE, "LHR", "JFK", false);

		assertThat(result.successful()).isTrue();
		assertThat(result.departureAirport()).isEqualTo("CDG");
		assertThat(result.arrivalAirport()).isEqualTo("XMN");
		fixture.server().verify();
	}

	@Test
	void acceptsAnAdjacentProviderDateWithoutRequiringAnOptionalDesignatorField() {
		Fixture fixture = fixture();
		fixture.server().expect(request -> { }).andRespond(withSuccess("""
				{"search_metadata":{"status":"Success"},"flight_result":{"dates":[{
				  "date":"2026-08-26","metadata":{"origin":"LHR","destination":"JFK"},
				  "departure_airport":{"id":"LHR","scheduled_time":"2026-08-26T10:00:00+01:00"},
				  "arrival_airport":{"id":"JFK","scheduled_time":"2026-08-26T13:00:00-04:00"}
				}]}}
				""", MediaType.APPLICATION_JSON));

		ProviderFlightResult result = fixture.provider().findFlight("BA117", DATE, null, null, false);

		assertThat(result.successful()).isTrue();
		assertThat(result.departureDate()).isEqualTo(DATE.plusDays(1));
		fixture.server().verify();
	}

	@Test
	void usesTheDocumentDepartureTimeToRankSameDateFlightResultCandidates() {
		Fixture fixture = fixture();
		fixture.server().expect(request -> { }).andRespond(withSuccess("""
				{"search_metadata":{"status":"Success"},"flight_result":{"flight_designator":"BA117","dates":[
				 {"date":"2026-08-25","metadata":{"origin":"LHR","destination":"JFK"},"departure_airport":{"id":"LHR","scheduled_time":"2026-08-25T09:00:00+01:00"},"arrival_airport":{"id":"JFK","scheduled_time":"2026-08-25T12:00:00-04:00"}},
				 {"date":"2026-08-25","metadata":{"origin":"LHR","destination":"JFK"},"departure_airport":{"id":"LHR","scheduled_time":"2026-08-25T18:00:00+01:00"},"arrival_airport":{"id":"JFK","scheduled_time":"2026-08-25T21:00:00-04:00"}}
				]}}
				""", MediaType.APPLICATION_JSON));

		ProviderFlightResult result = fixture.provider().findFlight(
				"BA117", DATE, "LHR", "JFK", "18:05", false);

		assertThat(result.scheduledDeparture().getHour()).isEqualTo(18);
		fixture.server().verify();
	}

	@Test
	void derivesOneConsistentMetadataRouteForGoogleFlightsFallback() {
		Fixture fixture = fixture();
		fixture.server().expect(request -> {
			var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
			assertThat(query.getFirst("engine")).isEqualTo("google");
		}).andRespond(withSuccess("""
				{
				  "search_metadata":{"status":"Success"},
				  "flight_result":{
				    "flight_designator":"BA117",
				    "dates":[
				      {"date":"2026-08-20","metadata":{"origin":"LHR","destination":"JFK"}},
				      {"date":"2026-08-21","metadata":{"origin":"LHR","destination":"JFK"}}
				    ]
				  }
				}
				""", MediaType.APPLICATION_JSON));
		fixture.server().expect(request -> {
			var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
			assertThat(query.getFirst("engine")).isEqualTo("google_flights");
			assertThat(query.getFirst("type")).isEqualTo("2");
			assertThat(query.getFirst("departure_id")).isEqualTo("LHR");
			assertThat(query.getFirst("arrival_id")).isEqualTo("JFK");
			assertThat(query.getFirst("outbound_date")).isEqualTo("2026-08-25");
			JsonNode selected = objectMapper.readTree(decoded(query.getFirst("selected_flights_json")));
			assertThat(selected.path("outbound").path(0).path("flight_number").asText()).isEqualTo("BA117");
			assertThat(selected.path("outbound").path(0).path("departure_id").asText()).isEqualTo("LHR");
			assertThat(selected.path("outbound").path(0).path("arrival_id").asText()).isEqualTo("JFK");
		}).andRespond(withSuccess("""
				{
				  "search_metadata":{"status":"Success","google_flights_url":"https://example.invalid/result"},
				  "selected_flights":[{"flights":[{
				    "flight_number":"BA 117",
				    "departure_airport":{"id":"LHR","name":"London Heathrow","time":"2026-08-25 10:00"},
				    "arrival_airport":{"id":"JFK","name":"John F. Kennedy","time":"2026-08-25 13:00"}
				  }]}]
				}
				""", MediaType.APPLICATION_JSON));

		ProviderFlightResult result = fixture.provider().findFlight("BA117", DATE, null, null, false);

		assertThat(result.successful()).isTrue();
		assertThat(result.quality()).isEqualTo("SCHEDULE");
		assertThat(result.departureAirport()).isEqualTo("LHR");
		assertThat(result.arrivalAirport()).isEqualTo("JFK");
		fixture.server().verify();
	}

	@Test
	void reportsMissingRouteHintAsRecoverableOutcome() {
		Fixture fixture = fixture();
		fixture.server().expect(request -> { }).andRespond(withSuccess("""
				{"search_metadata":{"status":"Success"},
				 "flight_result":{"flight_designator":"BA117","dates":[]}}
				""", MediaType.APPLICATION_JSON));

		ProviderFlightResult result = fixture.provider().findFlight("BA117", DATE, null, null, false);

		assertThat(result.outcome()).isEqualTo("MISSING_ROUTE_HINT");
		assertThat(result.errorCode()).isEqualTo("SERPAPI_MISSING_ROUTE_HINT");
		fixture.server().verify();
	}

	@Test
	void discoversTheOnlyExactFlightDateSelectedByTrustedRouteAndTimeHints() {
		Fixture fixture = fixture();
		fixture.server().expect(request -> {
			var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
			assertThat(query.getFirst("engine")).isEqualTo("google");
			assertThat(decoded(query.getFirst("q"))).isEqualTo("Flight BA117");
		}).andRespond(withSuccess("""
				{"search_metadata":{"status":"Success"},"flight_result":{"flight_designator":"BA117","dates":[
				 {"date":"2026-08-25","metadata":{"origin":"LHR","destination":"JFK"},"departure_airport":{"id":"LHR","scheduled_time":"2026-08-25T10:00:00+01:00"}},
				 {"date":"2026-08-26","metadata":{"origin":"LHR","destination":"JFK"},"departure_airport":{"id":"LHR","scheduled_time":"2026-08-26T18:00:00+01:00"}},
				 {"date":"2026-08-27","metadata":{"origin":"CDG","destination":"JFK"},"departure_airport":{"id":"CDG","scheduled_time":"2026-08-27T18:00:00+02:00"}}
				]}}
				""", MediaType.APPLICATION_JSON));

		LocalDate discovered = fixture.provider().discoverDepartureDate("BA 117", "LHR", "JFK", "18:05");

		assertThat(discovered).isEqualTo(LocalDate.of(2026, 8, 26));
		fixture.server().verify();
	}

	@Test
	void dateDiscoveryDoesNotGuessBetweenMultipleEquallyMatchedDates() {
		Fixture fixture = fixture();
		fixture.server().expect(request -> { }).andRespond(withSuccess("""
				{"search_metadata":{"status":"Success"},"flight_result":{"flight_designator":"BA117","dates":[
				 {"date":"2026-08-25","metadata":{"origin":"LHR","destination":"JFK"},"departure_airport":{"id":"LHR","scheduled_time":"2026-08-25T18:00:00+01:00"}},
				 {"date":"2026-08-26","metadata":{"origin":"LHR","destination":"JFK"},"departure_airport":{"id":"LHR","scheduled_time":"2026-08-26T18:00:00+01:00"}}
				]}}
				""", MediaType.APPLICATION_JSON));

		LocalDate discovered = fixture.provider().discoverDepartureDate("BA117", "LHR", "JFK", "18:05");

		assertThat(discovered).isNull();
		fixture.server().verify();
	}

	@Test
	void dateDiscoveryRequiresAnExplicitExactFlightDesignator() {
		Fixture fixture = fixture();
		fixture.server().expect(request -> { }).andRespond(withSuccess("""
				{"search_metadata":{"status":"Success"},"flight_result":{"flight_designator":"BA118","dates":[
				 {"date":"2026-08-25","metadata":{"origin":"LHR","destination":"JFK"},"departure_airport":{"id":"LHR","scheduled_time":"2026-08-25T18:00:00+01:00"}}
				]}}
				""", MediaType.APPLICATION_JSON));

		LocalDate discovered = fixture.provider().discoverDepartureDate("BA117", "LHR", "JFK", "18:05");

		assertThat(discovered).isNull();
		fixture.server().verify();
	}

	@ParameterizedTest
	@MethodSource("embeddedErrors")
	void classifiesSafeTopLevelSerpErrors(String body, String outcome, String errorCode) {
		Fixture fixture = fixture();
		fixture.server().expect(request -> { }).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		ProviderFlightResult result = fixture.provider().findFlight("BA117", DATE, null, null, false);

		assertThat(result.outcome()).isEqualTo(outcome);
		assertThat(result.errorCode()).isEqualTo(errorCode);
		fixture.server().verify();
	}

	static Stream<Arguments> embeddedErrors() {
		return Stream.of(
				Arguments.of("{\"search_metadata\":{\"status\":\"Error\"},\"error\":{\"message\":\"Missing required parameter q\"}}",
						"INVALID_REQUEST", "SERPAPI_INVALID_PROVIDER_REQUEST"),
				Arguments.of("{\"search_metadata\":{\"status\":\"Error\"},\"error\":\"Invalid API key\"}",
						"AUTHENTICATION_FAILED", "SERPAPI_AUTHENTICATION_FAILED"),
				Arguments.of("{\"search_metadata\":{\"status\":\"Error\"},\"error\":\"Account has run out of searches\"}",
						"FORBIDDEN_OR_QUOTA", "SERPAPI_QUOTA_EXCEEDED"),
				Arguments.of("{\"search_metadata\":{\"status\":\"Error\"},\"error\":\"No results found\"}",
						"NOT_FOUND", "SERPAPI_NO_RESULTS"),
				Arguments.of("{\"search_metadata\":{\"status\":\"Error\"}}",
						"UPSTREAM_ERROR", "SERPAPI_SEARCH_ERROR"));
	}

	@ParameterizedTest
	@MethodSource("badRequests")
	void classifiesHttp400WithoutReturningProviderText(String body, String outcome, String errorCode) {
		Fixture fixture = fixture();
		fixture.server().expect(request -> { }).andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON).body(body));

		ProviderFlightResult result = fixture.provider().findFlight("BA117", DATE, null, null, false);

		assertThat(result.outcome()).isEqualTo(outcome);
		assertThat(result.errorCode()).isEqualTo(errorCode);
		fixture.server().verify();
	}

	static Stream<Arguments> badRequests() {
		return Stream.of(
				Arguments.of("{\"error\":\"Missing required parameter departure_id\"}",
						"INVALID_REQUEST", "SERPAPI_INVALID_PROVIDER_REQUEST"),
				Arguments.of("{\"error\":\"Invalid API key\"}",
						"AUTHENTICATION_FAILED", "SERPAPI_AUTHENTICATION_FAILED"),
				Arguments.of("{\"detail\":\"Private request 123 was rejected\"}",
						"UPSTREAM_ERROR", "SERPAPI_HTTP_400"),
				Arguments.of("not-json", "UPSTREAM_ERROR", "SERPAPI_HTTP_400"));
	}

	@ParameterizedTest
	@CsvSource({
			"401, AUTHENTICATION_FAILED, SERPAPI_HTTP_401",
			"403, FORBIDDEN_OR_QUOTA, SERPAPI_HTTP_403",
			"404, UPSTREAM_ERROR, SERPAPI_HTTP_404",
			"429, RATE_LIMITED, SERPAPI_HTTP_429",
			"500, UPSTREAM_ERROR, SERPAPI_HTTP_500",
			"503, UPSTREAM_ERROR, SERPAPI_HTTP_503"
	})
	void classifiesHttpStatuses(int status, String outcome, String errorCode) {
		Fixture fixture = fixture();
		fixture.server().expect(request -> { }).andRespond(withStatus(HttpStatus.valueOf(status))
				.contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"private detail\"}"));

		ProviderFlightResult result = fixture.provider().findFlight("BA117", DATE, null, null, false);

		assertThat(result.outcome()).isEqualTo(outcome);
		assertThat(result.errorCode()).isEqualTo(errorCode);
		fixture.server().verify();
	}

	private Fixture fixture() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://serpapi.com");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		SerpApiFlightDataProvider provider = new SerpApiFlightDataProvider(
				objectMapper, builder.build(), () -> "stub-key");
		return new Fixture(provider, server);
	}

	private String decoded(String value) {
		return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private record Fixture(SerpApiFlightDataProvider provider, MockRestServiceServer server) {
	}
}
