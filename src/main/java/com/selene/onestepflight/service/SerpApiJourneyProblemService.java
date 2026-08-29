package com.selene.onestepflight.service;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.selene.onestepflight.dto.JourneyProblemRequest;
import com.selene.onestepflight.dto.JourneyProblemResponse;
import com.selene.onestepflight.dto.JourneyProblemResponse.AnswerStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class SerpApiJourneyProblemService {
	private static final Logger LOGGER = LoggerFactory.getLogger(SerpApiJourneyProblemService.class);
	private static final List<String> UNHELPFUL_PHRASES = List.of(
			"无法找到", "未找到", "找不到", "没有可靠方案",
			"could not find", "couldn't find", "unable to find", "no reliable solution",
			"no se pudo encontrar", "no pude encontrar", "no encontré", "ninguna solución fiable");

	private final JourneyProblemPromptBuilder promptBuilder;
	private final JourneyProblemAnswerExtractor answerExtractor;
	private final JourneyProblemLocalFallback localFallback;
	private final ObjectMapper objectMapper;
	private final RestClient primaryClient;
	private final RestClient recoveryClient;
	private final Supplier<String> apiKeySupplier;
	private final Clock clock;

	@Autowired
	public SerpApiJourneyProblemService(ObjectMapper objectMapper,
			JourneyProblemPromptBuilder promptBuilder,
			JourneyProblemAnswerExtractor answerExtractor,
			JourneyProblemLocalFallback localFallback) {
		this(objectMapper, promptBuilder, answerExtractor, localFallback,
				buildRestClient(Duration.ofSeconds(24)), buildRestClient(Duration.ofSeconds(12)),
				() -> System.getenv("SERPAPI_API_KEY"), Clock.systemUTC());
	}

	SerpApiJourneyProblemService(ObjectMapper objectMapper,
			JourneyProblemPromptBuilder promptBuilder,
			JourneyProblemAnswerExtractor answerExtractor,
			JourneyProblemLocalFallback localFallback,
			RestClient primaryClient,
			RestClient recoveryClient,
			Supplier<String> apiKeySupplier,
			Clock clock) {
		this.objectMapper = objectMapper;
		this.promptBuilder = promptBuilder;
		this.answerExtractor = answerExtractor;
		this.localFallback = localFallback;
		this.primaryClient = primaryClient;
		this.recoveryClient = recoveryClient;
		this.apiKeySupplier = apiKeySupplier;
		this.clock = clock;
	}

	private static RestClient buildRestClient(Duration readTimeout) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(3));
		requestFactory.setReadTimeout(readTimeout);
		return RestClient.builder().baseUrl("https://serpapi.com").requestFactory(requestFactory).build();
	}

	public JourneyProblemResponse solve(JourneyProblemRequest request) {
		JourneyProblemTrace trace = solveWithTrace(request);
		return new JourneyProblemResponse(trace.displayAnswer(), trace.responseStatus(), trace.retrievedAt());
	}

	JourneyProblemTrace solveWithTrace(JourneyProblemRequest request) {
		Instant retrievedAt = clock.instant();
		List<AttemptTrace> attempts = new ArrayList<>(2);
		AttemptTrace primary = attempt(AttemptType.PRIMARY, promptBuilder.buildPrimary(request),
				request.locale(), primaryClient);
		attempts.add(primary);
		if (primary.success()) {
			return new JourneyProblemTrace(List.copyOf(attempts), AnswerStatus.ANSWER,
					primary.extractedAnswer(), retrievedAt);
		}

		AttemptTrace recovery = attempt(AttemptType.RECOVERY, promptBuilder.buildRecovery(request),
				request.locale(), recoveryClient);
		attempts.add(recovery);
		if (recovery.success()) {
			return new JourneyProblemTrace(List.copyOf(attempts), AnswerStatus.ANSWER,
					recovery.extractedAnswer(), retrievedAt);
		}
		return new JourneyProblemTrace(List.copyOf(attempts), AnswerStatus.FALLBACK,
				localFallback.answer(request), retrievedAt);
	}

	private AttemptTrace attempt(AttemptType type, String prompt, String locale, RestClient client) {
		long started = System.nanoTime();
		String rawResponse = null;
		String extracted = null;
		String failureReason = null;
		try {
			String apiKey = apiKeySupplier.get();
			if (apiKey == null || apiKey.isBlank()) throw new AttemptFailure("SERPAPI_NOT_CONFIGURED");
			rawResponse = client.get().uri(getUri(prompt, locale, apiKey.trim()))
					.retrieve().toEntity(String.class).getBody();
			if (rawResponse == null || rawResponse.isBlank()) throw new AttemptFailure("EMPTY_RESPONSE");
			JsonNode root = objectMapper.readTree(rawResponse);
			String searchStatus = text(root.path("search_metadata").path("status"));
			if (!"Success".equalsIgnoreCase(searchStatus)) throw new AttemptFailure("SEARCH_NOT_SUCCESS");
			String rawAnswer = answerExtractor.rawText(root)
					.orElseThrow(() -> new AttemptFailure("EMPTY_ANSWER"));
			if (containsUnhelpfulPhrase(rawAnswer)) throw new AttemptFailure("UNHELPFUL_NOT_FOUND");
			extracted = answerExtractor.clean(rawAnswer)
					.orElseThrow(() -> new AttemptFailure("EMPTY_ANSWER"));
		} catch (Exception exception) {
			if (exception instanceof RestClientResponseException responseException) {
				rawResponse = responseException.getResponseBodyAsString();
			}
			failureReason = failureReason(exception);
		}
		long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
		LOGGER.info("journeyProblem attemptType={} elapsedMs={} outcome={}",
				type, elapsedMs, failureReason == null ? "ANSWER" : failureReason);
		return new AttemptTrace(type, prompt, rawResponse, extracted, failureReason, elapsedMs);
	}

	static URI getUri(String prompt, String locale, String apiKey) {
		return URI.create("https://serpapi.com/search?engine=google_ai_mode&q=" + encode(prompt)
				+ "&hl=" + encode(language(locale))
				+ "&continuable=false&no_cache=true&device=mobile&api_key=" + encode(apiKey));
	}

	static boolean containsUnhelpfulPhrase(String answer) {
		String normalized = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
		return UNHELPFUL_PHRASES.stream().anyMatch(normalized::contains);
	}

	private static String failureReason(Exception exception) {
		if (exception instanceof AttemptFailure attemptFailure) return attemptFailure.code;
		if (exception instanceof RestClientResponseException responseException) {
			return "HTTP_" + responseException.getStatusCode().value();
		}
		if (isTimeout(exception)) return "TIMEOUT";
		return exception.getClass().getSimpleName();
	}

	private static boolean isTimeout(Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof SocketTimeoutException) return true;
			current = current.getCause();
		}
		return throwable instanceof ResourceAccessException
				&& throwable.getMessage() != null
				&& throwable.getMessage().toLowerCase(Locale.ROOT).contains("timed out");
	}

	private static String language(String locale) {
		return "zh".equals(locale) ? "zh-CN" : "es".equals(locale) ? "es" : "en";
	}

	private static String text(JsonNode node) {
		return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText().trim() : null;
	}

	private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

	private static final class AttemptFailure extends RuntimeException {
		private final String code;
		private AttemptFailure(String code) { super(code); this.code = code; }
	}

	enum AttemptType { PRIMARY, RECOVERY }

	record AttemptTrace(
			AttemptType type,
			String prompt,
			String rawResponse,
			String extractedAnswer,
			String failureReason,
			long elapsedMs) {
		boolean success() { return failureReason == null && extractedAnswer != null; }
	}

	record JourneyProblemTrace(
			List<AttemptTrace> attempts,
			AnswerStatus responseStatus,
			String displayAnswer,
			Instant retrievedAt) { }
}
