package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.selene.onestepflight.dto.AirportResourcesRequest;
import com.selene.onestepflight.dto.AirportResourcesRequest.RefreshTarget;
import com.selene.onestepflight.dto.AirportResourcesResponse.ContactStatus;
import com.selene.onestepflight.dto.AirportResourcesResponse.MapResultType;
import com.selene.onestepflight.dto.AirportResourcesResponse.MapStatus;
import com.selene.onestepflight.service.SerpApiOfficialAirportDomainResolver.Resolution;

import tools.jackson.databind.ObjectMapper;

class SerpApiAirportMapLookupTest {
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-29T01:02:03Z"), ZoneOffset.UTC);

	@Test
	void malformedContactDiscoveryDoesNotBlockOfficialMap() {
		Fixture fixture = fixture(4, (engine, query) -> {
			if ("google_maps".equals(engine)) return maps();
			if ("google_ai_mode".equals(engine)) return successWithText("not JSON");
			if (query.contains("airport map terminal map")) return officialMap();
			return emptySearch();
		});

		var response = fixture.service().findResources(
				new AirportResourcesRequest("JFK", null, null, true, RefreshTarget.BOTH));

		assertThat(response.contact().status()).isEqualTo(ContactStatus.NOT_FOUND);
		assertThat(response.map().status()).isEqualTo(MapStatus.FOUND);
		assertThat(response.map().resultType()).isEqualTo(MapResultType.DIRECT_INTERACTIVE_MAP);
		assertThat(response.map().url()).isEqualTo("https://www.jfkairport.com/airport-maps");
		fixture.server().verify();
	}

	@Test
	void strongMapsPlaceFallsBackToExactAirportLocationWhenOfficialMapIsAbsent() {
		Fixture fixture = fixture(3, (engine, query) -> "google_maps".equals(engine) ? maps()
				: "google_ai_mode".equals(engine) ? strictEmptyMapAi() : emptySearch());

		var response = fixture.service().findResources(
				new AirportResourcesRequest("JFK", null, null, true, RefreshTarget.MAP));

		assertThat(response.map().status()).isEqualTo(MapStatus.AIRPORT_LOCATION);
		assertThat(response.map().resultType()).isEqualTo(MapResultType.GOOGLE_MAPS_AIRPORT_LOCATION);
		assertThat(response.map().url()).isEqualTo(
				"https://www.google.com/maps/place/?q=place_id%3AChIJR0lA1VBmwokR8BGfSBOyT-w");
		assertThat(response.rejectionReasons()).containsExactly(
				"OFFICIAL_MAP_NOT_VERIFIED_USING_EXACT_LOCATION");
		fixture.server().verify();
	}

	private static Fixture fixture(int count, BiFunction<String, String, String> response) {
		ObjectMapper mapper = new ObjectMapper();
		RestClient.Builder builder = RestClient.builder().baseUrl("https://serpapi.com");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
		server.expect(ExpectedCount.times(count), request -> { }).andRespond(request -> {
			var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
			return withSuccess(response.apply(query.getFirst("engine"), URLDecoder.decode(
					String.valueOf(query.getFirst("q")), StandardCharsets.UTF_8)), MediaType.APPLICATION_JSON)
					.createResponse(request);
		});
		SerpApiOfficialAirportDomainResolver resolver = mock(SerpApiOfficialAirportDomainResolver.class);
		when(resolver.resolve(any(AirportIdentity.class), nullable(String.class), anyString(), anyBoolean()))
				.thenReturn(Resolution.found(Set.of("jfkairport.com")));
		SerpApiAirportResourcesService service = new SerpApiAirportResourcesService(mapper, builder.build(),
				() -> "stub-key", CLOCK, new OfficialTravelSourcePolicy(), resolver, Runnable::run,
				new AirportIdentityCatalog(mapper));
		return new Fixture(service, server);
	}

	private static String maps() {
		return """
				{"search_metadata":{"status":"Success"},"place_results":{
				 "title":"John F. Kennedy International Airport","types":["International airport","Airport"],
				 "gps_coordinates":{"latitude":40.6446161,"longitude":-73.7797222},
				 "website":"https://www.jfkairport.com/","place_id":"ChIJR0lA1VBmwokR8BGfSBOyT-w"}}
				""";
	}

	private static String officialMap() {
		return """
				{"search_metadata":{"status":"Success"},"organic_results":[{
				 "title":"Airport interactive maps","link":"https://www.jfkairport.com/airport-maps",
				 "snippet":"Interactive airport terminal maps"}]}
				""";
	}

	private static String emptySearch() {
		return "{\"search_metadata\":{\"status\":\"Success\"},\"organic_results\":[]}";
	}

	private static String successWithText(String text) {
		return "{\"search_metadata\":{\"status\":\"Success\"},\"text_blocks\":[{\"snippet\":\"" + text + "\"}]}";
	}

	private static String strictEmptyMapAi() {
		return successWithText("{\\\"airportCode\\\":\\\"JFK\\\",\\\"airportConfirmed\\\":true,"
				+ "\\\"phoneCandidates\\\":[],\\\"contactCandidates\\\":[],\\\"mapCandidates\\\":[]}");
	}

	private record Fixture(SerpApiAirportResourcesService service, MockRestServiceServer server) { }
}
