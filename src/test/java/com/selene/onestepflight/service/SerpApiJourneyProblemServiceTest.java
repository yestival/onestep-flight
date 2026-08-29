package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.selene.onestepflight.dto.JourneyProblemRequest;
import com.selene.onestepflight.dto.JourneyProblemResponse.AnswerStatus;

import tools.jackson.databind.ObjectMapper;

class SerpApiJourneyProblemServiceTest {
	private static final String API_KEY = "secret-test-key-that-must-not-leak";

	@Test void normalPrimaryAnswerMakesOneRequest() {
		Fixture fixture = fixture();
		JourneyProblemRequest request = enRequest();
		fixture.server().expect(http -> assertThat(requestPrompt(http))
				.isEqualTo(fixture.builder().buildPrimary(request)))
				.andRespond(success("Please don’t worry. Keep checking the airport screens and ask airline staff if the delay changes.", "primary"));
		var trace = fixture.service().solveWithTrace(request);
		assertThat(trace.responseStatus()).isEqualTo(AnswerStatus.ANSWER);
		assertThat(trace.attempts()).hasSize(1);
		fixture.server().verify();
	}

	@Test void primaryHttpErrorUsesRecoveryPrompt() {
		Fixture fixture = fixture();
		fixture.server().expect(http -> { }).andRespond(withBadRequest());
		fixture.server().expect(http -> assertThat(requestPrompt(http))
				.isEqualTo(fixture.builder().buildRecovery(enRequest())))
				.andRespond(success(recoveryAnswer(), "recovery-http"));
		assertRecovered(fixture.service().solveWithTrace(enRequest()), "HTTP_400");
	}

	@Test void primaryTimeoutUsesRecoveryPrompt() {
		Fixture fixture = fixture();
		fixture.server().expect(http -> { }).andRespond(request -> { throw new SocketTimeoutException("timed out"); });
		fixture.server().expect(http -> assertThat(requestPrompt(http))
				.isEqualTo(fixture.builder().buildRecovery(enRequest())))
				.andRespond(success(recoveryAnswer(), "recovery-timeout"));
		assertRecovered(fixture.service().solveWithTrace(enRequest()), "TIMEOUT");
	}

	@Test void emptyPrimaryBodyUsesRecoveryPrompt() {
		Fixture fixture = fixture();
		fixture.server().expect(http -> { }).andRespond(withSuccess(
				"{\"search_metadata\":{\"status\":\"Success\",\"id\":\"empty\"}}", MediaType.APPLICATION_JSON));
		fixture.server().expect(http -> assertThat(requestPrompt(http))
				.isEqualTo(fixture.builder().buildRecovery(enRequest())))
				.andRespond(success(recoveryAnswer(), "recovery-empty"));
		assertRecovered(fixture.service().solveWithTrace(enRequest()), "EMPTY_ANSWER");
	}

	@Test void obviousNotFoundPrimaryUsesRecoveryPrompt() {
		Fixture fixture = fixture();
		fixture.server().expect(http -> { }).andRespond(success(
				"I could not find a reliable flight, so please try again.", "not-found"));
		fixture.server().expect(http -> assertThat(requestPrompt(http))
				.isEqualTo(fixture.builder().buildRecovery(enRequest())))
				.andRespond(success(recoveryAnswer(), "recovery-copy"));
		assertRecovered(fixture.service().solveWithTrace(enRequest()), "UNHELPFUL_NOT_FOUND");
	}

	@Test void successfulSecondAttemptReturnsAnswerStatusAndSecondText() {
		Fixture fixture = fixture();
		fixture.server().expect(http -> { }).andRespond(withServerError());
		fixture.server().expect(http -> { }).andRespond(success(recoveryAnswer(), "recovery-success"));
		var response = fixture.service().solve(enRequest());
		assertThat(response.status()).isEqualTo(AnswerStatus.ANSWER);
		assertThat(response.answer()).isEqualTo(recoveryAnswer());
	}

	@Test void twoFailedAttemptsReturnLocalFallback() {
		Fixture fixture = fixture();
		fixture.server().expect(http -> { }).andRespond(withServerError());
		fixture.server().expect(http -> { }).andRespond(withServerError());
		var response = fixture.service().solve(enRequest());
		assertThat(response.status()).isEqualTo(AnswerStatus.FALLBACK);
		assertThat(response.answer()).isEqualTo(
				"Please don’t worry. Keep your boarding pass and flight details, and explain the situation you described to airline staff so they can help confirm the next step.");
	}

	@Test void invalidJsonAlsoUsesRecoveryAndApiKeyIsAbsentFromTrace() {
		Fixture fixture = fixture();
		fixture.server().expect(http -> { }).andRespond(withSuccess("not-json", MediaType.TEXT_PLAIN));
		fixture.server().expect(http -> { }).andRespond(success(recoveryAnswer(), "recovery-json"));
		var trace = fixture.service().solveWithTrace(enRequest());
		assertThat(trace.attempts()).hasSize(2);
		assertThat(trace.toString()).doesNotContain(API_KEY);
		assertThat(trace.attempts()).allSatisfy(attempt -> {
			assertThat(String.valueOf(attempt.rawResponse())).doesNotContain(API_KEY);
			assertThat(String.valueOf(attempt.failureReason())).doesNotContain(API_KEY);
		});
	}

	@Test void obviousPhrasesAreIntentionallyMinimal() {
		assertThat(SerpApiJourneyProblemService.containsUnhelpfulPhrase("无法找到明确航班。")) .isTrue();
		assertThat(SerpApiJourneyProblemService.containsUnhelpfulPhrase("No pude encontrar un vuelo.")) .isTrue();
		assertThat(SerpApiJourneyProblemService.containsUnhelpfulPhrase("Please keep your boarding pass.")) .isFalse();
	}

	private static void assertRecovered(SerpApiJourneyProblemService.JourneyProblemTrace trace, String failure) {
		assertThat(trace.responseStatus()).isEqualTo(AnswerStatus.ANSWER);
		assertThat(trace.attempts()).hasSize(2);
		assertThat(trace.attempts().get(0).failureReason()).isEqualTo(failure);
		assertThat(trace.attempts().get(1).type()).isEqualTo(SerpApiJourneyProblemService.AttemptType.RECOVERY);
		assertThat(trace.displayAnswer()).isEqualTo(recoveryAnswer());
	}

	private static org.springframework.test.web.client.ResponseCreator success(String answer, String id) {
		return withSuccess("{\"search_metadata\":{\"status\":\"Success\",\"id\":\"" + id
				+ "\"},\"answer\":\"" + answer.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}",
				MediaType.APPLICATION_JSON);
	}

	private static String recoveryAnswer() {
		return "Please don’t worry. Keep your boarding pass and explain the situation to airline staff.";
	}

	private static JourneyProblemRequest enRequest() {
		return JourneyProblemTestFixtures.request("en", false, "My flight is delayed. What should I do?");
	}

	private static String requestPrompt(org.springframework.http.HttpRequest request) {
		String q = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("q");
		return URLDecoder.decode(q, StandardCharsets.UTF_8);
	}

	private static Fixture fixture() {
		ObjectMapper mapper = new ObjectMapper();
		RestClient.Builder clientBuilder = RestClient.builder().baseUrl("https://serpapi.com");
		MockRestServiceServer server = MockRestServiceServer.bindTo(clientBuilder).build();
		RestClient client = clientBuilder.build();
		JourneyProblemPromptBuilder builder = new JourneyProblemPromptBuilder();
		var service = new SerpApiJourneyProblemService(mapper, builder,
				new JourneyProblemAnswerExtractor(), new JourneyProblemLocalFallback(), client, client,
				() -> API_KEY, Clock.fixed(Instant.parse("2026-08-29T14:00:00Z"), ZoneOffset.UTC));
		return new Fixture(service, server, builder);
	}

	private record Fixture(SerpApiJourneyProblemService service, MockRestServiceServer server,
			JourneyProblemPromptBuilder builder) { }
}
