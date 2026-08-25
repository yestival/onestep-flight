package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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

import tools.jackson.databind.ObjectMapper;

class AeroDataBoxFlightDataProviderTest {

	private static final LocalDate DATE = LocalDate.of(2026, 8, 25);

	@Test
	void usesSingleDateEndpointAndTreatsRouteAsRankingHint() {
		Fixture fixture = fixture();
		fixture.server().expect(request -> {
			assertThat(request.getMethod()).isEqualTo(HttpMethod.GET);
			assertThat(request.getURI().getPath()).isEqualTo("/flights/number/BA117/2026-08-25");
			var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
			assertThat(query.getFirst("dateLocalRole")).isEqualTo("Departure");
			assertThat(query.getFirst("withAircraftImage")).isEqualTo("false");
			assertThat(query.getFirst("withLocation")).isEqualTo("false");
			assertThat(request.getHeaders().getFirst("X-RapidAPI-Key")).isEqualTo("stub-key");
			assertThat(request.getHeaders().getFirst("X-RapidAPI-Host"))
					.isEqualTo("aerodatabox.p.rapidapi.com");
		}).andRespond(withSuccess("""
				[
				  {
				    "number":"BA 117",
				    "departure":{"airport":{"iata":"CDG","name":"Paris"},
				      "scheduledTime":{"local":"2026-08-25T10:00:00+02:00"}},
				    "arrival":{"airport":{"iata":"XMN","name":"Xiamen"},
				      "scheduledTime":{"local":"2026-08-25T22:00:00+08:00"}},
				    "status":"Scheduled"
				  },
				  {
				    "number":"BA117",
				    "departure":{"airport":{"iata":"LHR","name":"London"},
				      "scheduledTime":{"local":"2026-08-24T10:00:00+01:00"}},
				    "arrival":{"airport":{"iata":"JFK","name":"New York"},
				      "scheduledTime":{"local":"2026-08-24T13:00:00-04:00"}},
				    "status":"Scheduled"
				  },
				  {
				    "number":"BA118",
				    "departure":{"airport":{"iata":"LHR"},
				      "scheduledTime":{"local":"2026-08-25T09:00:00+01:00"}},
				    "arrival":{"airport":{"iata":"JFK"},
				      "scheduledTime":{"local":"2026-08-25T12:00:00-04:00"}}
				  }
				]
				""", MediaType.APPLICATION_JSON));

		ProviderFlightResult result = fixture.provider().findFlight("BA117", DATE, "LHR", "JFK", false);

		assertThat(result.successful()).isTrue();
		assertThat(result.departureDate()).isEqualTo(DATE);
		assertThat(result.departureAirport()).isEqualTo("CDG");
		assertThat(result.arrivalAirport()).isEqualTo("XMN");
		fixture.server().verify();
	}

	@Test
	void keepsTheProviderDateWhenOnlyAnAdjacentDayCandidateExists() {
		Fixture fixture = fixture();
		fixture.server().expect(request -> { }).andRespond(withSuccess("""
				[{"number":"BA117",
				  "departure":{"airport":{"iata":"LHR"},"scheduledTime":{"local":"2026-08-24T23:30:00+01:00"}},
				  "arrival":{"airport":{"iata":"JFK"},"scheduledTime":{"local":"2026-08-25T02:10:00-04:00"}}}]
				""", MediaType.APPLICATION_JSON));

		ProviderFlightResult result = fixture.provider().findFlight("BA117", DATE, "LHR", "JFK", false);

		assertThat(result.successful()).isTrue();
		assertThat(result.departureDate()).isEqualTo(DATE.minusDays(1));
		fixture.server().verify();
	}

	@Test
	void usesTheDocumentDepartureTimeAsATieBreakingHint() {
		Fixture fixture = fixture();
		fixture.server().expect(request -> { }).andRespond(withSuccess("""
				[
				 {"number":"BA117","departure":{"airport":{"iata":"LHR"},"scheduledTime":{"local":"2026-08-25T09:00:00+01:00"}},"arrival":{"airport":{"iata":"JFK"},"scheduledTime":{"local":"2026-08-25T12:00:00-04:00"}}},
				 {"number":"BA117","departure":{"airport":{"iata":"LHR"},"scheduledTime":{"local":"2026-08-25T18:00:00+01:00"}},"arrival":{"airport":{"iata":"JFK"},"scheduledTime":{"local":"2026-08-25T21:00:00-04:00"}}}
				]
				""", MediaType.APPLICATION_JSON));

		ProviderFlightResult result = fixture.provider().findFlight(
				"BA117", DATE, "LHR", "JFK", "2026-08-25T18:10", false);

		assertThat(result.scheduledDeparture().getHour()).isEqualTo(18);
		fixture.server().verify();
	}

	@ParameterizedTest
	@MethodSource("badRequests")
	void mapsOnlyAllowlistedStructuredBadRequestReasons(String body, String outcome, String errorCode) {
		Fixture fixture = fixture();
		fixture.server().expect(request -> assertThat(request.getURI().getPath())
				.isEqualTo("/flights/number/BA117/2026-08-25"))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST)
						.contentType(MediaType.APPLICATION_JSON).body(body));

		ProviderFlightResult result = fixture.provider().findFlight("BA117", DATE, null, null, false);

		assertThat(result.outcome()).isEqualTo(outcome);
		assertThat(result.errorCode()).isEqualTo(errorCode);
		fixture.server().verify();
	}

	static Stream<Arguments> badRequests() {
		return Stream.of(
				Arguments.of("{\"message\":\"Invalid flight number format\"}",
						"INVALID_REQUEST", "INVALID_FLIGHT_NUMBER"),
				Arguments.of("{\"error\":{\"detail\":\"date must be formatted as YYYY-MM-DD\"}}",
						"INVALID_REQUEST", "INVALID_REQUEST_DATE"),
				Arguments.of("{\"detail\":\"Date is outside the data coverage\"}",
						"INVALID_REQUEST", "DATE_OUT_OF_COVERAGE"),
				Arguments.of("{\"error\":\"This endpoint is not available for your current plan\"}",
						"FORBIDDEN_OR_QUOTA", "PLAN_OR_PROVIDER_RESTRICTION"),
				Arguments.of("{\"message\":\"Missing required parameter searchBy\"}",
						"INVALID_REQUEST", "INVALID_PROVIDER_REQUEST"),
				Arguments.of("{\"message\":\"Rejected value BA117 for account 123\",\"account\":\"private\"}",
						"UPSTREAM_ERROR", "AERODATABOX_HTTP_400"),
				Arguments.of("not-json", "UPSTREAM_ERROR", "AERODATABOX_HTTP_400"));
	}

	@ParameterizedTest
	@CsvSource({
			"401, AUTHENTICATION_FAILED, AERODATABOX_HTTP_401",
			"403, FORBIDDEN_OR_QUOTA, AERODATABOX_HTTP_403",
			"429, RATE_LIMITED, AERODATABOX_HTTP_429",
			"500, UPSTREAM_ERROR, AERODATABOX_HTTP_500",
			"503, UPSTREAM_ERROR, AERODATABOX_HTTP_503"
	})
	void classifiesNon400HttpFailuresWithoutExposingResponse(int status, String outcome, String errorCode) {
		Fixture fixture = fixture();
		fixture.server().expect(request -> { })
				.andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
						.body("{\"message\":\"private provider detail\",\"api_key\":\"must-not-escape\"}"));

		ProviderFlightResult result = fixture.provider().findFlight("BA117", DATE, null, null, false);

		assertThat(result.outcome()).isEqualTo(outcome);
		assertThat(result.errorCode()).isEqualTo(errorCode);
		fixture.server().verify();
	}

	private Fixture fixture() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://aerodatabox.p.rapidapi.com");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		AeroDataBoxFlightDataProvider provider = new AeroDataBoxFlightDataProvider(
				new ObjectMapper(), builder.build(), () -> "stub-key");
		return new Fixture(provider, server);
	}

	private record Fixture(AeroDataBoxFlightDataProvider provider, MockRestServiceServer server) {
	}
}
