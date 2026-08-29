package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.selene.onestepflight.dto.AirportResourcesRequest;
import com.selene.onestepflight.dto.AirportResourcesRequest.RefreshTarget;
import com.selene.onestepflight.dto.AirportResourcesResponse;
import com.selene.onestepflight.dto.AirportResourcesResponse.ContactStatus;
import com.selene.onestepflight.dto.AirportResourcesResponse.MapResultType;
import com.selene.onestepflight.service.SerpApiOfficialAirportDomainResolver.Resolution;

import tools.jackson.databind.ObjectMapper;

class SerpApiAirportResourcesServiceTest {
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-29T01:02:03Z"), ZoneOffset.UTC);
	private static final String DOMAIN = "jfkairport.com";

	@Test
	void codeOnlyBothRequestUsesCanonicalMapsStrictJsonAndRealDecisionTrace() {
		Fixture fixture = fixture(Runnable::run, DOMAIN);
		stub(fixture, 2, (engine, query) -> switch (engine) {
			case "google_maps" -> mapsJfk("(718) 244-4444", DOMAIN);
			case "google_ai_mode" -> strictContact("JFK", true, "+1 718 244 4444",
					"https://www.jfkairport.com/contact-us", true);
			default -> officialMap(DOMAIN);
		});

		AirportResourcesResponse response = fixture.service().findResources(
				new AirportResourcesRequest("JFK", null, null, false, RefreshTarget.BOTH));

		assertThat(response.identityStatus()).isEqualTo(IdentityStatus.VERIFIED.name());
		assertThat(response.contact().status()).isEqualTo(ContactStatus.PHONE_FOUND);
		assertThat(response.contact().telUri()).isEqualTo("tel:+17182444444");
		assertThat(response.contact().contactUrl()).isNull();
		assertThat(response.map().resultType()).isEqualTo(MapResultType.DIRECT_INTERACTIVE_MAP);
		assertThat(response.serpApiRequestCount()).isEqualTo(2).isLessThanOrEqualTo(6);
		assertThat(response.rejectionReasons()).doesNotContain(SerpApiAirportResourcesService.AIRPORT_IDENTITY_MISMATCH);
		assertThat(response.decisionTrace().candidates()).anySatisfy(candidate -> {
			assertThat(candidate.decision()).isEqualTo(AirportIdentityCandidate.Decision.ACCEPTED);
			assertThat(candidate.selectedFields()).isNotEmpty();
		});
		assertThat(response.decisionTrace().httpStages()).extracting(DecisionTrace.HttpStage::stage)
				.containsExactlyInAnyOrder("IDENTITY_MAPS", "MAP_SEARCH");
		assertThat(response.decisionTrace().candidates())
				.filteredOn(candidate -> "IDENTITY_SEARCH_SKIPPED_STRONG_MAPS".equals(candidate.candidateId()))
				.singleElement().satisfies(candidate ->
						assertThat(candidate.candidateSpecificReasons())
								.containsExactly("IDENTITY_SEARCH_SKIPPED_STRONG_MAPS"));
		assertThat(response.decisionTrace().aiStages()).isEmpty();
		verify(fixture.resolver(), never()).resolve(any(AirportIdentity.class), nullable(String.class),
				anyString(), anyBoolean());
		fixture.server().verify();
	}

	@Test
	void contactOnlyStrongMapsPhoneSkipsIdentitySearchEvenWithoutWebsite() {
		Fixture fixture = fixture(Runnable::run, DOMAIN);
		stub(fixture, 1, (engine, query) -> mapsJfk("(718) 244-4444", null));

		var response = fixture.service().findResources(
				new AirportResourcesRequest("JFK", null, null, true, RefreshTarget.CONTACT));

		assertThat(response.identityStatus()).isEqualTo(IdentityStatus.VERIFIED.name());
		assertThat(response.contact().telUri()).isEqualTo("tel:+17182444444");
		assertThat(response.contact().contactUrl()).isNull();
		assertThat(response.serpApiRequestCount()).isEqualTo(1);
		assertThat(response.decisionTrace().httpStages()).extracting(DecisionTrace.HttpStage::stage)
				.containsExactly("IDENTITY_MAPS");
		assertThat(response.decisionTrace().candidates()).extracting(DecisionTrace.Entry::candidateId)
				.contains("IDENTITY_SEARCH_SKIPPED_STRONG_MAPS");
		verify(fixture.resolver(), never()).resolve(any(AirportIdentity.class), nullable(String.class),
				anyString(), anyBoolean());
		fixture.server().verify();
	}

	@Test
	void bothRequestWithoutMapsWebsiteStillRunsIdentitySearchForMapAuthority() {
		Fixture fixture = fixture(Runnable::run, DOMAIN);
		stub(fixture, 2, (engine, query) -> "google_maps".equals(engine)
				? mapsJfk("(718) 244-4444", null) : officialMap(DOMAIN));

		var response = fixture.service().findResources(
				new AirportResourcesRequest("JFK", null, null, true, RefreshTarget.BOTH));

		assertThat(response.identityStatus()).isEqualTo(IdentityStatus.VERIFIED.name());
		assertThat(response.map().resultType()).isEqualTo(MapResultType.DIRECT_INTERACTIVE_MAP);
		assertThat(response.decisionTrace().candidates()).extracting(DecisionTrace.Entry::candidateId)
				.doesNotContain("IDENTITY_SEARCH_SKIPPED_STRONG_MAPS");
		verify(fixture.resolver()).resolve(any(AirportIdentity.class), nullable(String.class),
				anyString(), anyBoolean());
		fixture.server().verify();
	}

	@Test
	void nonBooleanConfirmationAndMissingCandidateSourceCannotOverrideStrongMapsPhone() {
		Fixture badBoolean = fixture(Runnable::run, DOMAIN);
		stub(badBoolean, 1, (engine, query) -> "google_maps".equals(engine)
				? mapsJfk("(718) 244-4444", DOMAIN)
				: strictContact("JFK", "John F. Kennedy Airport", "+1 999 555 0100",
						"https://www.jfkairport.com/contact-us", true));

		var first = badBoolean.service().findResources(
				new AirportResourcesRequest("JFK", null, null, true, RefreshTarget.CONTACT));
		assertThat(first.contact().telUri()).isEqualTo("tel:+17182444444");
		assertThat(first.contact().aiDedicatedPhoneField()).isFalse();
		badBoolean.server().verify();

		Fixture missingSource = fixture(Runnable::run, DOMAIN);
		stub(missingSource, 1, (engine, query) -> "google_maps".equals(engine)
				? mapsJfk("(718) 244-4444", DOMAIN)
				: strictContact("JFK", true, "+1 999 555 0100", null, false));
		var second = missingSource.service().findResources(
				new AirportResourcesRequest("JFK", null, null, true, RefreshTarget.CONTACT));
		assertThat(second.contact().telUri()).isEqualTo("tel:+17182444444");
		assertThat(second.contact().contactUrl()).isNull();
		missingSource.server().verify();
	}

	@Test
	void roleBoundLongPhoneFromVerifiedPassengerSearchSnippetIsAcceptedAtomically() {
		Fixture fixture = fixture(Runnable::run, "operator.example");
		stub(fixture, 3, (engine, query) -> switch (engine) {
			case "google_maps" -> mapsFor("LHR", "operator.example");
			case "google_ai_mode" -> strictContact("LHR", true, null, null, false);
			default -> passengerContactSnippet("operator.example");
		});

		var response = fixture.service().findResources(
				new AirportResourcesRequest("LHR", null, null, true, RefreshTarget.CONTACT));

		assertThat(response.contact().telUri()).isEqualTo("tel:+443443351801");
		assertThat(response.contact().contactUrl()).isEqualTo("https://operator.example/passengers/contact");
		assertThat(response.contact().sourceUrl()).isEqualTo("https://operator.example/passengers/contact");
		assertThat(response.decisionTrace().candidates()).filteredOn(candidate -> "CONTACT".equals(candidate.stage()))
				.filteredOn(candidate -> candidate.selectedFields().contains("contact.phone"))
				.singleElement().satisfies(candidate -> {
					assertThat(candidate.rawPhone()).isEqualTo("0344 335 1801");
					assertThat(candidate.selectedFields()).contains("contact.contactUrl");
				});
		fixture.server().verify();
	}

	@Test
	void naturalLanguageContactUsesOnlyRoleBoundPhoneFromAnOfficialStructuredReference() {
		Fixture fixture = fixture(Runnable::run, DOMAIN);
		stub(fixture, 2, (engine, query) -> "google_maps".equals(engine)
				? mapsJfk(null, DOMAIN) : naturalContact(DOMAIN));

		var response = fixture.service().findResources(
				new AirportResourcesRequest("JFK", null, null, true, RefreshTarget.CONTACT));

		assertThat(response.contact().telUri()).isEqualTo("tel:+17182444444");
		assertThat(response.contact().contactUrl()).isEqualTo("https://jfkairport.com/contact-us?lang=en");
		assertThat(response.decisionTrace().aiStages()).singleElement().satisfies(stage -> {
			assertThat(stage.responseMode()).isEqualTo(AiResponseCandidateExtractor.ResponseMode.NATURAL_LANGUAGE);
			assertThat(stage.referenceCandidates()).isEqualTo(1);
			assertThat(stage.selectedCandidates()).isPositive();
			assertThat(stage.diagnostics()).contains("AI_NON_JSON_REFERENCES_PARSED");
		});
		fixture.server().verify();
	}

	@Test
	void naturalLanguageMapReferencesUseTheClassifierAndDoNotCreateAnOfficialExternalLinkChain() {
		Fixture fixture = fixture(Runnable::run, DOMAIN);
		stub(fixture, 3, (engine, query) -> {
			if ("google_maps".equals(engine)) return mapsJfk(null, DOMAIN);
			if ("google_ai_mode".equals(engine)) return naturalMap(DOMAIN);
			return "{\"search_metadata\":{\"status\":\"Success\"},\"organic_results\":[]}";
		});

		var response = fixture.service().findResources(
				new AirportResourcesRequest("JFK", null, null, true, RefreshTarget.MAP));

		assertThat(response.map().url()).isEqualTo("https://jfkairport.com/floor-guide?terminal=4");
		assertThat(response.map().resultType()).isEqualTo(MapResultType.OFFICIAL_MAP_LANDING_PAGE);
		assertThat(response.decisionTrace().candidates())
				.filteredOn(candidate -> String.valueOf(candidate.rawUrl()).contains("vendor.example"))
				.singleElement().satisfies(candidate -> {
					assertThat(candidate.decision()).isEqualTo(AirportIdentityCandidate.Decision.REJECTED);
					assertThat(candidate.candidateSpecificReasons())
							.contains("EXTERNAL_MAP_WITHOUT_OFFICIAL_LINK_CHAIN",
									"AI_REFERENCE_NOT_OFFICIAL_DOMAIN");
				});
		assertThat(response.decisionTrace().aiStages()).singleElement()
				.satisfies(stage -> assertThat(stage.selectedCandidates()).isEqualTo(1));
		fixture.server().verify();
	}

	@Test
	void forceRefreshBypassesFoundCacheAndSendsNoCacheTrue() {
		Fixture fixture = fixture(Runnable::run, DOMAIN);
		stub(fixture, 2, (engine, query) -> "google_maps".equals(engine)
				? mapsJfk("(718) 244-4444", DOMAIN)
				: strictContact("JFK", true, "+1 718 244 4444",
						"https://www.jfkairport.com/contact-us", true));

		AirportResourcesRequest normal = new AirportResourcesRequest("JFK", null, null, false, RefreshTarget.CONTACT);
		var first = fixture.service().findResources(normal);
		var cached = fixture.service().findResources(normal);
		var forced = fixture.service().findResources(
				new AirportResourcesRequest("JFK", null, null, true, RefreshTarget.CONTACT));

		assertThat(first.fromCache()).isFalse();
		assertThat(cached.fromCache()).isTrue();
		assertThat(cached.serpApiRequestCount()).isZero();
		assertThat(forced.fromCache()).isFalse();
		assertThat(fixture.noCacheValues()).containsExactlyInAnyOrder("false", "true");
		fixture.server().verify();
	}

	@Test
	void unknownIataReturnsSafeUnverifiedResultWithoutCallingUpstream() {
		Fixture fixture = fixture(Runnable::run, DOMAIN);
		var response = fixture.service().findResources(
				new AirportResourcesRequest("ZZZ", null, null, true, RefreshTarget.BOTH));

		assertThat(response.identityStatus()).isEqualTo(IdentityStatus.UNVERIFIED.name());
		assertThat(response.contact().phoneDisplay()).isNull();
		assertThat(response.contact().contactUrl()).isNull();
		assertThat(response.serpApiRequestCount()).isZero();
		fixture.server().verify();
	}

	@Test
	void fourConcurrentRequestsDoNotStarveNestedWork() throws Exception {
		ExecutorService serviceExecutor = Executors.newFixedThreadPool(8);
		ExecutorService callers = Executors.newFixedThreadPool(4);
		try {
			Fixture fixture = fixture(serviceExecutor, "operator.example");
			stub(fixture, 8, (engine, query) -> {
				String code = List.of("JFK", "LHR", "HND", "DXB").stream()
						.filter(query::contains).findFirst().orElseThrow();
				return "google_maps".equals(engine) ? mapsFor(code, "operator.example")
						: strictContact(code, true, null, "https://operator.example/passenger-contact", true);
			});
			List<CompletableFuture<AirportResourcesResponse>> futures = List.of("JFK", "LHR", "HND", "DXB")
					.stream().map(code -> CompletableFuture.supplyAsync(() -> fixture.service().findResources(
							new AirportResourcesRequest(code, null, null, true, RefreshTarget.CONTACT)), callers)).toList();

			CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
			assertThat(futures).allSatisfy(future -> {
				assertThat(future.join().identityStatus()).isEqualTo(IdentityStatus.VERIFIED.name());
				assertThat(future.join().contact().status()).isEqualTo(ContactStatus.CONTACT_PAGE_ONLY);
				assertThat(future.join().serpApiRequestCount()).isEqualTo(2);
			});
			fixture.server().verify();
		} finally {
			callers.shutdownNow();
			serviceExecutor.shutdownNow();
		}
	}

	private static String strictContact(String code, Object confirmed, String phone, String sourceUrl,
			boolean includeReference) {
		String phoneCandidates = phone == null ? "[]" : "[{\"value\":\"" + phone
				+ "\",\"role\":\"GENERAL_PASSENGER\",\"scope\":\"INTERNATIONAL\""
				+ (sourceUrl == null ? "" : ",\"sourceUrl\":\"" + sourceUrl + "\"") + "}]";
		String contactCandidates = phone == null && sourceUrl != null
				? "[{\"url\":\"" + sourceUrl + "\",\"title\":\"Passenger contact and feedback\","
						+ "\"role\":\"GENERAL_PASSENGER\",\"sourceUrl\":\"" + sourceUrl + "\"}]" : "[]";
		String confirmedJson = confirmed instanceof Boolean ? confirmed.toString() : "\"" + confirmed + "\"";
		String payload = "{\"airportCode\":\"" + code + "\",\"airportConfirmed\":" + confirmedJson
				+ ",\"sourceLanguage\":\"en\",\"phoneCandidates\":" + phoneCandidates
				+ ",\"contactCandidates\":" + contactCandidates + ",\"mapCandidates\":[]}";
		String references = includeReference && sourceUrl != null
				? ",\"references\":[{\"link\":\"" + sourceUrl
						+ "\",\"title\":\"Passenger contact and feedback\",\"snippet\":\"General passenger customer service and enquiries\"}]"
				: "";
		return "{\"search_metadata\":{\"status\":\"Success\"},\"text_blocks\":[{\"snippet\":"
				+ jsonString(payload) + "}]" + references + "}";
	}

	private static String mapsJfk(String phone, String domain) {
		return maps("John F. Kennedy International Airport", 40.6446161, -73.7797222, phone, domain);
	}

	private static String mapsFor(String code, String domain) {
		return switch (code) {
			case "JFK" -> maps("John F. Kennedy International Airport", 40.6446161, -73.7797222, null, domain);
			case "LHR" -> maps("Heathrow Airport", 51.4679903, -0.4550471, null, domain);
			case "HND" -> maps("Haneda Airport", 35.5482964, 139.7779951, null, domain);
			case "DXB" -> maps("Dubai International Airport", 25.2515401, 55.3683066, null, domain);
			default -> throw new IllegalArgumentException(code);
		};
	}

	private static String maps(String title, double latitude, double longitude, String phone, String domain) {
		return "{\"search_metadata\":{\"status\":\"Success\"},\"place_results\":{"
				+ "\"title\":\"" + title + "\",\"types\":[\"International airport\",\"Airport\"],"
				+ "\"gps_coordinates\":{\"latitude\":" + latitude + ",\"longitude\":" + longitude + "},"
				+ (domain == null ? "" : "\"website\":\"https://" + domain + "/\",")
				+ "\"place_id\":\"ChIJ123456789_ABC\""
				+ (phone == null ? "" : ",\"phone\":\"" + phone + "\"") + "}}";
	}

	private static String officialMap(String domain) {
		return "{\"search_metadata\":{\"status\":\"Success\"},\"organic_results\":[{"
				+ "\"title\":\"Airport interactive maps\",\"link\":\"https://" + domain
				+ "/at-airport/airport-maps\",\"snippet\":\"Interactive airport terminal map\"}]}";
	}

	private static String passengerContactSnippet(String domain) {
		return "{\"search_metadata\":{\"status\":\"Success\"},\"organic_results\":[{"
				+ "\"title\":\"Passenger contact centre\",\"link\":\"https://" + domain
				+ "/passengers/contact\",\"snippet\":\"Call us Airport contact centre: 0344 335 1801. "
				+ "Accessibility textphone: 0333 200 7459\"}]}";
	}

	private static String naturalContact(String domain) {
		return "{\"search_metadata\":{\"status\":\"Success\"},"
				+ "\"answer\":\"Use the airport contact centre.\",\"references\":[{"
				+ "\"link\":\"https://" + domain + "/contact-us?lang=en\","
				+ "\"title\":\"Passenger contact centre\","
				+ "\"snippet\":\"Customer service: +1 718 244 4444\"}]}";
	}

	private static String naturalMap(String domain) {
		return "{\"search_metadata\":{\"status\":\"Success\"},"
				+ "\"answer\":\"See the cited floor guide.\",\"references\":["
				+ "{\"link\":\"https://" + domain + "/floor-guide?terminal=4\",\"title\":\"Floor Guide\","
				+ "\"snippet\":\"Official terminal floor map\"},"
				+ "{\"link\":\"https://vendor.example/parking-map\",\"title\":\"Airport parking map\"}]}";
	}

	private static String jsonString(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static void stub(Fixture fixture, int count, BiFunction<String, String, String> response) {
		fixture.server().expect(ExpectedCount.times(count), request -> { }).andRespond(request -> {
			var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
			String engine = query.getFirst("engine");
			String decoded = URLDecoder.decode(String.valueOf(query.getFirst("q")), StandardCharsets.UTF_8);
			fixture.noCacheValues().add(query.getFirst("no_cache"));
			return withSuccess(response.apply(engine, decoded), MediaType.APPLICATION_JSON).createResponse(request);
		});
	}

	private static Fixture fixture(java.util.concurrent.Executor executor, String domain) {
		ObjectMapper mapper = new ObjectMapper();
		RestClient.Builder builder = RestClient.builder().baseUrl("https://serpapi.com");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
		SerpApiOfficialAirportDomainResolver resolver = mock(SerpApiOfficialAirportDomainResolver.class);
		when(resolver.resolve(any(AirportIdentity.class), nullable(String.class), anyString(), anyBoolean()))
				.thenReturn(Resolution.found(Set.of(domain)));
		SerpApiAirportResourcesService service = new SerpApiAirportResourcesService(mapper, builder.build(),
				() -> "stub-key", CLOCK, new OfficialTravelSourcePolicy(), resolver, executor,
				new AirportIdentityCatalog(mapper));
		return new Fixture(service, server, Collections.synchronizedList(new ArrayList<>()), resolver);
	}

	private record Fixture(SerpApiAirportResourcesService service, MockRestServiceServer server,
			List<String> noCacheValues, SerpApiOfficialAirportDomainResolver resolver) { }
}
