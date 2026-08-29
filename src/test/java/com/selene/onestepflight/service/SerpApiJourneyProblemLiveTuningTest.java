package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.JourneyProblemRequest;

import tools.jackson.databind.ObjectMapper;

class SerpApiJourneyProblemLiveTuningTest {
	private static final Path OUTPUT = Path.of("output", "serpapi-journey-problem-ai-mode-simple");
	private static final Pattern SENTENCE = Pattern.compile("[^。！？.!?]+[。！？.!?]+(?:[”’\\\"']+)?");
	private static final Pattern FORBIDDEN_FORMAT = Pattern.compile(
			"(?im)https?://|www\\.|^\\s{0,3}#{1,6}\\s+|^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)");

	@Test
	void runTwelveSimpleLiveScenariosAndSaveActualInputsAndOutputs() throws Exception {
		assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_SERPAPI_JOURNEY_PROBLEM_LIVE")),
				"Set RUN_SERPAPI_JOURNEY_PROBLEM_LIVE=true for explicit live debugging");
		String apiKey = System.getenv("SERPAPI_API_KEY");
		assumeTrue(apiKey != null && !apiKey.isBlank(), "SERPAPI_API_KEY is required");

		ObjectMapper mapper = new ObjectMapper();
		var service = new SerpApiJourneyProblemService(mapper, new JourneyProblemPromptBuilder(),
				new JourneyProblemAnswerExtractor(), new JourneyProblemLocalFallback());
		List<Scenario> scenarios = scenarios();
		List<Map<String, Object>> records = new ArrayList<>();
		Files.createDirectories(OUTPUT);

		for (int index = 0; index < scenarios.size(); index++) {
			Scenario scenario = scenarios.get(index);
			var trace = service.solveWithTrace(scenario.request());
			Map<String, Object> record = record(index + 1, scenario, trace);
			String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record);
			assertThat(json).doesNotContain(apiKey);
			Files.writeString(OUTPUT.resolve("run-%02d.json".formatted(index + 1)), json, StandardCharsets.UTF_8);
			records.add(record);
		}

		String allJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(records);
		assertThat(allJson).doesNotContain(apiKey);
		Files.writeString(OUTPUT.resolve("all-runs.json"), allJson, StandardCharsets.UTF_8);
		Files.writeString(OUTPUT.resolve("summary.md"), markdown(records), StandardCharsets.UTF_8);
		assertThat(records).hasSize(12);
	}

	private static Map<String, Object> record(int run, Scenario scenario,
			SerpApiJourneyProblemService.JourneyProblemTrace trace) {
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("run", run);
		record.put("locale", scenario.request().locale());
		record.put("scenario", scenario.name());
		record.put("input", scenario.request());
		List<Map<String, Object>> attempts = new ArrayList<>();
		for (var attempt : trace.attempts()) {
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("type", attempt.type());
			value.put("prompt", attempt.prompt());
			value.put("rawResponse", attempt.rawResponse());
			value.put("cleanedAnswer", attempt.extractedAnswer());
			value.put("failureReason", attempt.failureReason());
			value.put("elapsedMs", attempt.elapsedMs());
			attempts.add(value);
		}
		record.put("attempts", attempts);
		record.put("finalStatus", trace.responseStatus());
		record.put("finalAnswer", trace.displayAnswer());
		record.put("retrievedAt", trace.retrievedAt());
		record.put("review", review(scenario, trace.displayAnswer()));
		return record;
	}

	private static Map<String, Boolean> review(Scenario scenario, String answer) {
		String lower = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
		Map<String, Boolean> review = new LinkedHashMap<>();
		review.put("correctLanguage", correctLanguage(lower, scenario.request().locale()));
		review.put("reassuresTraveller", containsAny(lower,
				"先别着急", "别着急", "请放心", "don’t worry", "don't worry", "stay calm",
				"no te preocupes", "mantén la calma", "manten la calma"));
		review.put("givesNextAction", containsAny(lower,
				"请", "前往", "留意", "联系", "询问", "keep", "ask", "go", "check", "contact",
				"conserva", "pregunta", "ve ", "consulta", "explica"));
		review.put("claimsCompletedRebooking", containsAny(lower,
				"已经改签", "已改签", "you have been rebooked", "you are rebooked", "ya estás reubicado", "ya estas reubicado"));
		review.put("historicalTreatedAsCurrent", scenario.historical() && containsAny(lower,
				"今天登机", "今天的延误", "在登机口等待", "board today", "wait at the gate",
				"embarcar hoy", "espera en la puerta"));
		review.put("containsNotFoundSystemCopy", SerpApiJourneyProblemService.containsUnhelpfulPhrase(answer));
		review.put("overTwoSentences", sentenceCount(answer) > 2);
		review.put("containsLinkTitleOrList", FORBIDDEN_FORMAT.matcher(answer == null ? "" : answer).find());
		return review;
	}

	private static boolean correctLanguage(String text, String locale) {
		long han = text.codePoints().filter(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN).count();
		if ("zh".equals(locale)) return han >= 4;
		if (han > 0) return false;
		if ("es".equals(locale)) return containsAny(text, " vuelo", " aerolínea", " personal", " mostrador", " puerta", " conexión");
		return containsAny(text, " flight", " airline", " staff", " desk", " gate", " connection");
	}

	private static int sentenceCount(String answer) {
		Matcher matcher = SENTENCE.matcher(answer == null ? "" : answer);
		int count = 0;
		while (matcher.find()) count++;
		return count == 0 && answer != null && !answer.isBlank() ? 1 : count;
	}

	private static boolean containsAny(String text, String... values) {
		for (String value : values) if (text.contains(value)) return true;
		return false;
	}

	private static String markdown(List<Map<String, Object>> records) {
		StringBuilder value = new StringBuilder("# Journey problem: 12 real SerpApi AI Mode runs\n\n");
		for (Map<String, Object> record : records) {
			value.append("## Run ").append(record.get("run")).append(": ").append(record.get("scenario")).append("\n\n")
					.append("- Locale: ").append(record.get("locale")).append('\n')
					.append("- Final status: ").append(record.get("finalStatus")).append('\n')
					.append("- Final answer: ").append(record.get("finalAnswer")).append('\n')
					.append("- Review: ").append(record.get("review")).append("\n\n");
		}
		return value.toString();
	}

	private static List<Scenario> scenarios() {
		List<Scenario> values = new ArrayList<>();
		addLocale(values, "zh",
				"我的当前航班晚点了，我不需要转机，现在应该怎么做？",
				"我的航班延误，可能赶不上后面的转机航班，请告诉我下一步。",
				"我的当前航班已经取消了，我现在该怎么办？",
				"这是一个月前延误的航班，我现在需要处理当时的问题。");
		addLocale(values, "en",
				"My current flight is delayed and I do not have a connection. What should I do now?",
				"My flight is delayed and I may miss my onward connection. Please tell me the next step.",
				"My current flight has been cancelled. What should I do now?",
				"This is a delayed flight from one month ago, and I need help resolving what happened then.");
		addLocale(values, "es",
				"Mi vuelo actual está retrasado y no tengo conexión. ¿Qué debo hacer ahora?",
				"Mi vuelo está retrasado y puedo perder mi conexión. Indícame el siguiente paso.",
				"Mi vuelo actual ha sido cancelado. ¿Qué debo hacer ahora?",
				"Este vuelo se retrasó hace un mes y ahora necesito ayuda para resolver lo que ocurrió entonces.");
		return List.copyOf(values);
	}

	private static void addLocale(List<Scenario> values, String locale, String delay, String connection,
			String cancelled, String historical) {
		values.add(new Scenario(locale + " current delay, no connection",
				JourneyProblemTestFixtures.request(locale, false, delay), false));
		values.add(new Scenario(locale + " delayed connection at risk",
				JourneyProblemTestFixtures.request(locale, true, connection), false));
		values.add(new Scenario(locale + " cancelled current flight",
				JourneyProblemTestFixtures.request(locale, false, cancelled,
						JourneyProblemTestFixtures.cancelledFlight()), false));
		values.add(new Scenario(locale + " historical delay one month ago",
				JourneyProblemTestFixtures.request(locale, false, historical,
						JourneyProblemTestFixtures.historicalDelayedFlight()), true));
	}

	private record Scenario(String name, JourneyProblemRequest request, boolean historical) { }
}
