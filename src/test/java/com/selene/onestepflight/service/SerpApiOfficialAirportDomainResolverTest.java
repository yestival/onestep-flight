package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.ObjectMapper;

class SerpApiOfficialAirportDomainResolverTest {
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-29T00:00:00Z"), ZoneOffset.UTC);
	private static final AirportIdentity AIRPORT = new AirportIdentity("EXM", "ZZZZ",
			"Example International Airport", "Example City", "ZZ", "Exampleland",
			12.3, 45.6, "large_airport", true, List.of("Example Airport"));

	@Test
	void canonicalQueryUsesLocalEntityAndStructuredAirportEntityCanVerifyDomain() {
		Fixture fixture = fixture();
		fixture.server().expect(request -> {
			var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
			assertThat(query.getFirst("engine")).isEqualTo("google");
			assertThat(query.getFirst("no_cache")).isEqualTo("false");
			assertThat(URLDecoder.decode(query.getFirst("q"), StandardCharsets.UTF_8))
					.isEqualTo("Example International Airport Example City Exampleland EXM official airport website");
		}).andRespond(withSuccess("""
				{"search_metadata":{"status":"Success","id":"request-1"},"knowledge_graph":{
				 "title":"Example International Airport (EXM)","type":"International airport",
				 "country":"Exampleland","website":{"url":"https://www.example.test/home"}}}
				""", MediaType.APPLICATION_JSON));

		var resolution = fixture.resolver().resolve(AIRPORT, null, "dataset-a", false);

		assertThat(resolution.domains()).containsExactly("example.test");
		assertThat(resolution.status()).isEqualTo(SerpApiOfficialAirportDomainResolver.ResolutionStatus.FOUND);
		assertThat(resolution.httpStages()).singleElement().satisfies(stage -> {
			assertThat(stage.httpStatus()).isEqualTo(200);
			assertThat(stage.providerRequestId()).isEqualTo("request-1");
			assertThat(stage.safeBodyFingerprint()).startsWith("sha256:");
		});
		fixture.server().verify();
	}

	@Test
	void ordinarySearchTextRemainsProvisionalAndHostnameIataIsNeverSufficient() {
		Fixture fixture = fixture();
		fixture.server().expect(request -> { }).andRespond(withSuccess("""
				{"search_metadata":{"status":"Success"},"organic_results":[
				 {"link":"https://www.exm-airport.test/","title":"Go to official website","source":"Directory"},
				 {"link":"https://operator.example/contact","title":"Example International Airport EXM",
				  "source":"Operator","snippet":"Passenger information for Example City."}
				]}
				""", MediaType.APPLICATION_JSON));

		var resolution = fixture.resolver().resolve(AIRPORT, null, "dataset-a", false);

		assertThat(resolution.domains()).isEmpty();
		assertThat(resolution.status()).isEqualTo(SerpApiOfficialAirportDomainResolver.ResolutionStatus.PARTIAL);
		assertThat(resolution.domainEvidence()).anySatisfy(item -> {
			assertThat(item.host()).isEqualTo("operator.example");
			assertThat(item.authority()).isEqualTo(DomainAuthority.PROVISIONAL_SEARCH);
		});
		fixture.server().verify();
	}

	@Test
	void forceRefreshBypassesFoundCacheAndDatasetVersionSeparatesCacheEntries() {
		Fixture fixture = fixture();
		for (int i = 0; i < 3; i++) {
			fixture.server().expect(request -> { }).andRespond(withSuccess("""
					{"search_metadata":{"status":"Success"},"knowledge_graph":{
					 "title":"Example International Airport EXM","type":"Airport","country":"Exampleland",
					 "website":"https://example.test/"}}
					""", MediaType.APPLICATION_JSON));
		}

		var first = fixture.resolver().resolve(AIRPORT, null, "dataset-a", false);
		var cached = fixture.resolver().resolve(AIRPORT, null, "dataset-a", false);
		var forced = fixture.resolver().resolve(AIRPORT, null, "dataset-a", true);
		var newDataset = fixture.resolver().resolve(AIRPORT, null, "dataset-b", false);

		assertThat(first.fromCache()).isFalse();
		assertThat(cached.fromCache()).isTrue();
		assertThat(forced.fromCache()).isFalse();
		assertThat(newDataset.fromCache()).isFalse();
		fixture.server().verify();
	}

	@Test
	void forceRefreshRecoversFromCachedError() {
		Fixture fixture = fixture();
		fixture.server().expect(request -> { }).andRespond(withSuccess(
				"{\"search_metadata\":{\"status\":\"Error\"}}", MediaType.APPLICATION_JSON));
		fixture.server().expect(request -> { }).andRespond(withSuccess("""
				{"search_metadata":{"status":"Success"},"knowledge_graph":{
				 "title":"Example International Airport EXM","type":"Airport","country":"Exampleland",
				 "website":"https://example.test/"}}
				""", MediaType.APPLICATION_JSON));

		assertThat(fixture.resolver().resolve(AIRPORT, null, "dataset-a", false).status())
				.isEqualTo(SerpApiOfficialAirportDomainResolver.ResolutionStatus.ERROR);
		assertThat(fixture.resolver().resolve(AIRPORT, null, "dataset-a", false).fromCache()).isTrue();
		assertThat(fixture.resolver().resolve(AIRPORT, null, "dataset-a", true).domains())
				.containsExactly("example.test");
		fixture.server().verify();
	}

	@Test
	void structuredSearchWithDifferentCountryTextStaysUnverifiedWithoutCoordinates() {
		Fixture fixture = fixture();
		AirportIdentity canonical = new AirportIdentity("TST", "TSTA", "Istanbul Airport", "Istanbul",
				"TR", "Turkey", 41.274874, 28.732136, "large_airport", true, List.of());
		fixture.server().expect(request -> { }).andRespond(withSuccess("""
				{"search_metadata":{"status":"Success"},"knowledge_graph":{
				 "title":"Istanbul Airport","type":"International airport","country":"Türkiye",
				 "website":"https://airport.example/"}}
				""", MediaType.APPLICATION_JSON));

		var resolution = fixture.resolver().resolve(canonical, null, "dataset-a", false);

		assertThat(resolution.status()).isEqualTo(SerpApiOfficialAirportDomainResolver.ResolutionStatus.NOT_FOUND);
		assertThat(resolution.identityCandidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.decision()).isEqualTo(AirportIdentityCandidate.Decision.REJECTED);
			assertThat(candidate.reason()).startsWith("INSUFFICIENT_CANONICAL_IDENTITY_SIGNALS")
					.contains("COUNTRY_TEXT_UNRECOGNIZED_OR_DIFFERENT");
		});
		fixture.server().verify();
	}

	private static Fixture fixture() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://serpapi.com");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		var resolver = new SerpApiOfficialAirportDomainResolver(new ObjectMapper(), builder.build(),
				() -> "stub-key", CLOCK, new OfficialTravelSourcePolicy());
		return new Fixture(resolver, server);
	}

	private record Fixture(SerpApiOfficialAirportDomainResolver resolver, MockRestServiceServer server) { }
}
