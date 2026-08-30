package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.selene.onestepflight.dto.JourneyProblemRequest;
import com.selene.onestepflight.dto.JourneyProblemRequest.CurrentFlight;
import com.selene.onestepflight.dto.JourneyProblemRequest.FlightChangeInput;
import com.selene.onestepflight.dto.ProviderFlightResult;
import com.selene.onestepflight.dto.ResolveFlightRequest;
import com.selene.onestepflight.dto.ResolvedFlightContext;
import com.selene.onestepflight.service.airline.AirlineRecord;
import com.selene.onestepflight.service.airline.AirlineRegistry;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = "spring.main.web-application-type=none")
class SerpApiJourneyProblemCrossFlightLiveTest {
	private static final Path OUTPUT = Path.of("output",
			"serpapi-journey-problem-ai-mode-simple-cross-flight");
	private static final LocalDate DEPARTURE_DATE = LocalDate.of(2026, 8, 29);
	private static final Pattern SENTENCE = Pattern.compile("[^。！？.!?]+[。！？.!?]+(?:[”’\\\"']+)?");
	private static final AirlineRegistry AIRLINES = AirlineRegistry.loadDefault();

	@Autowired
	private ResolvedFlightService resolvedFlightService;

	@Autowired
	private JourneyProblemPromptBuilder promptBuilder;

	@Autowired
	private SerpApiJourneyProblemService journeyProblemService;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void runEightRealCrossFlightScenariosThroughProductionServices() throws Exception {
		assumeTrue(Boolean.getBoolean("runSerpApiJourneyProblemCrossFlightLive"),
				"Set -DrunSerpApiJourneyProblemCrossFlightLive=true for explicit live regression");
		String serpApiKey = System.getenv("SERPAPI_API_KEY");
		assumeTrue(serpApiKey != null && !serpApiKey.isBlank(), "SERPAPI_API_KEY is required");

		Files.createDirectories(OUTPUT);
		if (Boolean.getBoolean("crossFlightReviewOnly")) {
			List<Map<String, Object>> reviewed = new ArrayList<>();
			for (int run = 1; run <= 8; run++) reviewed.add(existingRecord(run));
			writeAggregate(reviewed);
			return;
		}
		List<Scenario> scenarios = scenarios();
		List<QueryCapture> queries = new ArrayList<>();

		// Finish all production flight lookups first. No Journey Problem AI request is made
		// unless every selected flight independently resolves to a live project result.
		for (Scenario scenario : scenarios) {
			ResolveFlightRequest query = new ResolveFlightRequest(
					scenario.flightNumber(), DEPARTURE_DATE, false, null, null, null, null, true);
			List<Map<String, Object>> queryAttempts = new ArrayList<>();
			ResolvedFlightContext result = null;
			for (int attempt = 1; attempt <= 2 && result == null; attempt++) {
				try {
					result = resolvedFlightService.resolve(query);
					queryAttempts.add(queryAttempt(attempt, "SUCCESS", null, result.providerResults()));
				} catch (AllFlightProvidersUnavailableException exception) {
					queryAttempts.add(queryAttempt(attempt, "FAILED", exception.code(),
							exception.providerResults()));
					if (attempt == 1) Thread.sleep(2_000L);
				}
			}
			if (result == null) {
				Map<String, Object> failure = new LinkedHashMap<>();
				failure.put("flightNumber", scenario.flightNumber());
				failure.put("flightQueryRequest", query);
				failure.put("queryAttempts", queryAttempts);
				String failureJson = pretty(failure);
				assertNoApiKeys(failureJson);
				Files.writeString(OUTPUT.resolve("flight-query-failure-" + scenario.flightNumber() + ".json"),
						failureJson, StandardCharsets.UTF_8);
				throw new AssertionError(scenario.flightNumber()
						+ " did not return a live project flight result after one transient retry");
			}
			assertThat(result.live())
					.as("%s must be supplied by a live project flight query", scenario.flightNumber())
					.isTrue();
			assertThat(result.normalizedFlightNumber()).isEqualTo(scenario.flightNumber());
			queries.add(new QueryCapture(scenario, query, result, List.copyOf(queryAttempts)));
			Thread.sleep(1_200L);
		}

		assertCoverage(queries);
		List<Map<String, Object>> records = new ArrayList<>();
		int resumeAfter = Integer.getInteger("crossFlightResumeAfter", 0);
		for (int index = 0; index < queries.size(); index++) {
			QueryCapture capture = queries.get(index);
			if (index < resumeAfter) {
				Map<String, Object> existing = existingRecord(index + 1);
				records.add(existing);
				writeAggregate(records);
				continue;
			}
			JourneyProblemRequest request = journeyRequest(capture.scenario(), capture.result());
			String primaryPrompt = promptBuilder.buildPrimary(request);
			SerpApiJourneyProblemService.JourneyProblemTrace trace =
					journeyProblemService.solveWithTrace(request);
			assertThat(trace.attempts().get(0).prompt()).isEqualTo(primaryPrompt);

			Map<String, Object> record = record(index + 1, capture, request, trace);
			String runJson = pretty(record);
			assertNoApiKeys(runJson);
			Files.writeString(OUTPUT.resolve("run-%02d.json".formatted(index + 1)),
					runJson, StandardCharsets.UTF_8);
			records.add(record);
			writeAggregate(records);

			@SuppressWarnings("unchecked")
			List<String> issues = (List<String>) record.get("issues");
			assertThat(issues)
					.as("Stop on the first genuine simple-product acceptance failure")
					.isEmpty();
		}

		assertThat(records).hasSize(8);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> existingRecord(int run) throws Exception {
		Path path = OUTPUT.resolve("run-%02d.json".formatted(run));
		assertThat(path).exists();
		Map<String, Object> record = objectMapper.readValue(Files.readString(path), Map.class);
		CurrentFlight flight = objectMapper.convertValue(record.get("currentFlight"), CurrentFlight.class);
		JourneyProblemRequest request = new JourneyProblemRequest(
				(Boolean) record.get("hasConnection"), (String) record.get("passengerStatement"),
				(String) record.get("locale"), flight);
		Map<String, Map<String, Object>> acceptance = acceptance(
				request, (String) record.get("finalDisplayBody"));
		record.put("airline", airline((String) record.get("flightNumber")));
		record.put("manualAcceptance", acceptance);
		List<String> issues = issues(acceptance);
		record.put("issues", issues);
		record.put("manualReviewStatus", issues.isEmpty() ? "PASS" : "FAIL");
		record.put("manualReviewNote",
				"已依据实际最终正文逐项核对七项简单产品验收标准；未按措辞风格额外扣分。");
		String json = pretty(record);
		assertNoApiKeys(json);
		Files.writeString(path, json, StandardCharsets.UTF_8);
		return record;
	}

	private Map<String, Object> record(int run, QueryCapture capture,
			JourneyProblemRequest request,
			SerpApiJourneyProblemService.JourneyProblemTrace trace) {
		Scenario scenario = capture.scenario();
		ResolvedFlightContext result = capture.result();
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("run", run);
		record.put("preferredFlightNumber", scenario.flightNumber());
		record.put("flightReplaced", false);
		record.put("replacementReason", null);
		record.put("flightNumber", result.normalizedFlightNumber());
		record.put("departureDate", result.departureDate());
		record.put("airline", airline(result.normalizedFlightNumber()));
		record.put("departureAirport", result.departureAirport());
		record.put("departureAirportName", result.departureAirportName());
		record.put("arrivalAirport", result.arrivalAirport());
		record.put("arrivalAirportName", result.arrivalAirportName());
		record.put("problemAirport", request.currentFlight().problemAirport());
		record.put("problemPhase", request.currentFlight().problemPhase());
		record.put("region", scenario.region());
		record.put("locale", request.locale());
		record.put("hasConnection", request.hasConnection());
		record.put("passengerStatement", request.passengerStatement());
		record.put("flightQueryRequest", capture.query());
		record.put("flightQueryAttempts", capture.queryAttempts());
		record.put("flightQueryLive", result.live());
		record.put("flightQueryDataSource", result.liveDataSource());
		record.put("flightQuerySourceStatus", providerStatuses(result.providerResults()));
		record.put("resolvedFlightContext", result);
		record.put("currentFlight", request.currentFlight());
		record.put("actualPrimaryPrompt", trace.attempts().get(0).prompt());
		record.put("recoveryTriggered", trace.attempts().size() > 1);
		record.put("attempts", attemptRecords(trace));
		record.put("finalPath", finalPath(trace));
		record.put("finalStatus", trace.responseStatus());
		record.put("finalDisplayBody", trace.displayAnswer());
		record.put("retrievedAt", trace.retrievedAt());

		Map<String, Map<String, Object>> acceptance = acceptance(request, trace.displayAnswer());
		record.put("manualAcceptance", acceptance);
		List<String> issues = issues(acceptance);
		record.put("issues", issues);
		return record;
	}

	private static List<String> issues(Map<String, Map<String, Object>> acceptance) {
		return acceptance.entrySet().stream()
				.filter(entry -> !Boolean.TRUE.equals(entry.getValue().get("pass")))
				.map(entry -> entry.getKey() + ": " + entry.getValue().get("conclusion"))
				.toList();
	}

	private static JourneyProblemRequest journeyRequest(Scenario scenario,
			ResolvedFlightContext result) {
		List<FlightChangeInput> latestChanges = result.latestChanges().stream()
				.map(change -> new FlightChangeInput(change.type(), change.oldValue(),
						change.newValue(), change.critical(), change.detectedAt()))
				.toList();
		CurrentFlight currentFlight = new CurrentFlight(
				result.normalizedFlightNumber(),
				result.departureDate(),
				result.departureAirport(),
				result.departureAirportName(),
				result.arrivalAirport(),
				result.arrivalAirportName(),
				result.departureAirport(),
				result.departureAirportName(),
				"DEPARTURE",
				result.scheduledDeparture(),
				result.estimatedDeparture(),
				result.actualDeparture(),
				result.effectiveDeparture(),
				result.scheduledArrival(),
				result.estimatedArrival(),
				result.actualArrival(),
				result.timeContext() == null ? null : result.timeContext().boardingTime(),
				result.terminal(),
				result.arrivalTerminal(),
				result.gate(),
				result.status(),
				result.delayMinutes(),
				result.arrivalDelayMinutes(),
				result.codeshare(),
				result.dataLastUpdatedAt(),
				result.dataRetrievedAt(),
				result.timeContext() == null ? result.retrievedAt() : result.timeContext().serverNow(),
				result.timeContext() == null || result.timeContext().departureLocalNow() == null
						? null : result.timeContext().departureLocalNow().toString(),
				latestChanges);
		return new JourneyProblemRequest(scenario.hasConnection(), scenario.passengerStatement(),
				scenario.locale(), currentFlight);
	}

	private static Map<String, Object> providerStatuses(
			Map<String, ProviderFlightResult> providerResults) {
		Map<String, Object> statuses = new LinkedHashMap<>();
		providerResults.forEach((provider, result) -> {
			Map<String, Object> status = new LinkedHashMap<>();
			status.put("outcome", result.outcome());
			status.put("errorCode", result.errorCode());
			status.put("quality", result.quality());
			status.put("live", result.live());
			status.put("fromCache", result.fromCache());
			status.put("retrievedAt", result.retrievedAt());
			statuses.put(provider, status);
		});
		return statuses;
	}

	private static Map<String, Object> queryAttempt(int attempt, String outcome, String failureCode,
			Map<String, ProviderFlightResult> providerResults) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("attempt", attempt);
		value.put("outcome", outcome);
		value.put("failureCode", failureCode);
		value.put("providerStatuses", providerStatuses(providerResults));
		return value;
	}

	private static List<Map<String, Object>> attemptRecords(
			SerpApiJourneyProblemService.JourneyProblemTrace trace) {
		List<Map<String, Object>> attempts = new ArrayList<>();
		for (SerpApiJourneyProblemService.AttemptTrace attempt : trace.attempts()) {
			Map<String, Object> value = new LinkedHashMap<>();
			value.put("type", attempt.type());
			value.put("prompt", attempt.prompt());
			value.put("rawSerpApiResponse", attempt.rawResponse());
			value.put("cleanedAnswer", attempt.extractedAnswer());
			value.put("failureReason", attempt.failureReason());
			value.put("elapsedMs", attempt.elapsedMs());
			attempts.add(value);
		}
		return attempts;
	}

	private static String finalPath(SerpApiJourneyProblemService.JourneyProblemTrace trace) {
		if (trace.responseStatus() == com.selene.onestepflight.dto.JourneyProblemResponse.AnswerStatus.FALLBACK) {
			return "FALLBACK";
		}
		return trace.attempts().size() > 1 ? "RECOVERY" : "PRIMARY";
	}

	private static Map<String, Map<String, Object>> acceptance(JourneyProblemRequest request,
			String answer) {
		String normalized = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
		Map<String, Map<String, Object>> values = new LinkedHashMap<>();
		boolean correctLanguage = correctLanguage(normalized, request.locale());
		values.put("1_correctLanguage", conclusion(correctLanguage,
				correctLanguage ? "回答使用所选 locale 的语言。" : "回答语言与所选 locale 不一致。"));

		boolean related = relatedToFlight(normalized, request.currentFlight());
		values.put("2_relatedToFlightAndProblem", conclusion(related,
				related ? "回答围绕当前航班或乘客说明。" : "回答与当前航班和问题缺少可见关联。"));

		boolean actionable = hasNextAction(normalized);
		values.put("3_givesNextAction", conclusion(actionable,
				actionable ? "回答给出了乘客现在可执行的下一步。" : "回答没有提供下一步行动。"));

		boolean calm = calming(normalized, actionable);
		values.put("4_reassuringOrCalm", conclusion(calm,
				calm ? "回答包含安抚或平静、礼貌的行动引导。" : "回答缺少正常的安抚或平静引导。"));

		boolean noSystemFailure = !containsSystemFailure(answer);
		values.put("5_noSearchOrSystemFailureCopy", conclusion(noSystemFailure,
				noSystemFailure ? "未向用户显示搜索失败或系统失败式文字。" : "出现搜索或系统失败式文字。"));

		boolean routeNotReversed = !routeReversed(normalized, request.currentFlight());
		values.put("6_routeNotReversed", conclusion(routeNotReversed,
				routeNotReversed ? "未把出发机场、到达机场或航线说反。" : "回答把出发和到达方向说反。"));

		int sentences = sentenceCount(answer);
		boolean atMostTwo = sentences <= 2;
		values.put("7_atMostTwoSentences", conclusion(atMostTwo,
				(atMostTwo ? "最终正文不超过两句话（" : "最终正文超过两句话（") + sentences + " 句）。"));
		return values;
	}

	private static Map<String, Object> conclusion(boolean pass, String conclusion) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("pass", pass);
		value.put("conclusion", conclusion);
		return value;
	}

	private static boolean correctLanguage(String text, String locale) {
		long han = text.codePoints()
				.filter(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN)
				.count();
		if ("zh".equals(locale)) return han >= 4;
		if (han > 0) return false;
		if ("es".equals(locale)) {
			return containsAny(text, "vuelo", "aerolínea", "aerolinea", "personal", "mostrador",
					"puerta", "conexión", "conexion", "dirígete", "dirigete", "conserva");
		}
		return containsAny(text, "flight", "airline", "staff", "desk", "gate", "connection",
				"boarding", "heathrow");
	}

	private static boolean relatedToFlight(String text, CurrentFlight flight) {
		return containsAny(text,
				lower(flight.flightNumber()), lower(flight.departureAirport()), lower(flight.arrivalAirport()),
				lower(flight.departureAirportName()), lower(flight.arrivalAirportName()),
				"航班", "机场", "登机", "flight", "airport", "boarding", "vuelo", "aeropuerto", "embarque");
	}

	private static boolean hasNextAction(String text) {
		return containsAny(text,
				"请", "前往", "保留", "联系", "询问", "查看", "留意", "确认",
				"please", "keep", "ask", "go to", "check", "contact", "head to", "speak to",
				"conserva", "pregunta", "dirígete", "dirigete", "acérquese", "acerquese",
				"acuda", "consulta", "contacta", "habla", "ve al", "ve a la");
	}

	private static boolean calming(String text, boolean actionable) {
		return containsAny(text,
				"别着急", "不要着急", "请放心", "保持冷静", "深呼吸",
				"don't worry", "don’t worry", "stay calm", "remain calm", "take a breath",
				"no te preocupes", "mantén la calma", "manten la calma", "mantenga la calma",
				"comprendo su preocupación", "comprendo su preocupacion", "respira")
				|| (actionable && !containsAny(text, "!", "紧急", "panic", "pánico", "panico"));
	}

	private static boolean containsSystemFailure(String answer) {
		String text = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
		return SerpApiJourneyProblemService.containsUnhelpfulPhrase(answer)
				|| containsAny(text, "搜索失败", "系统失败", "系统错误", "search failed",
						"system failure", "system error", "falló la búsqueda", "fallo la búsqueda",
						"error del sistema");
	}

	private static boolean routeReversed(String text, CurrentFlight flight) {
		List<String> departures = nonBlank(lower(flight.departureAirport()), lower(flight.departureAirportName()));
		List<String> arrivals = nonBlank(lower(flight.arrivalAirport()), lower(flight.arrivalAirportName()));
		for (String arrival : arrivals) {
			for (String departure : departures) {
				if (containsAny(text,
						"from " + arrival + " to " + departure,
						"de " + arrival + " a " + departure,
						"desde " + arrival + " hasta " + departure,
						"从" + arrival + "到" + departure,
						"从 " + arrival + " 到 " + departure)) return true;
			}
		}
		return false;
	}

	private static int sentenceCount(String answer) {
		Matcher matcher = SENTENCE.matcher(answer == null ? "" : answer);
		int count = 0;
		while (matcher.find()) count++;
		return count == 0 && answer != null && !answer.isBlank() ? 1 : count;
	}

	private void writeAggregate(List<Map<String, Object>> records) throws Exception {
		String allJson = pretty(records);
		assertNoApiKeys(allJson);
		Files.writeString(OUTPUT.resolve("all-runs.json"), allJson, StandardCharsets.UTF_8);
		String summary = markdown(records);
		assertNoApiKeys(summary);
		Files.writeString(OUTPUT.resolve("summary.md"), summary, StandardCharsets.UTF_8);
	}

	private String pretty(Object value) {
		return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
	}

	private static String markdown(List<Map<String, Object>> records) {
		Map<String, Long> pathCounts = new LinkedHashMap<>();
		pathCounts.put("PRIMARY", records.stream().filter(r -> "PRIMARY".equals(r.get("finalPath"))).count());
		pathCounts.put("RECOVERY", records.stream().filter(r -> "RECOVERY".equals(r.get("finalPath"))).count());
		pathCounts.put("FALLBACK", records.stream().filter(r -> "FALLBACK".equals(r.get("finalPath"))).count());
		StringBuilder value = new StringBuilder("# Journey Problem simple cross-flight live regression\n\n")
				.append("- Completed runs: ").append(records.size()).append(" / 8\n")
				.append("- Final paths: ").append(pathCounts).append("\n\n");
		for (Map<String, Object> record : records) {
			value.append("## Run ").append(record.get("run")).append(": ")
					.append(record.get("flightNumber")).append("\n\n")
					.append("- Route: ").append(record.get("departureAirport")).append(" → ")
					.append(record.get("arrivalAirport")).append('\n')
					.append("- Airline: ").append(record.get("airline")).append('\n')
					.append("- Locale / connection / live: ").append(record.get("locale")).append(" / ")
					.append(record.get("hasConnection")).append(" / ").append(record.get("flightQueryLive")).append('\n')
					.append("- Passenger statement: ").append(record.get("passengerStatement")).append('\n')
					.append("- Final path: ").append(record.get("finalPath")).append('\n')
					.append("- Final answer: ").append(record.get("finalDisplayBody")).append('\n')
					.append("- Acceptance: ").append(record.get("manualAcceptance")).append('\n')
					.append("- Issues: ").append(record.get("issues")).append("\n\n");
		}
		return value.toString();
	}

	private static void assertCoverage(List<QueryCapture> queries) {
		assertThat(queries).hasSize(8);
		assertThat(queries.stream().map(query -> query.result().normalizedFlightNumber()).distinct()).hasSize(8);
		assertThat(queries.stream().map(query -> query.result().departureAirport()).distinct().count())
				.isGreaterThanOrEqualTo(6);
		assertThat(queries.stream().map(query -> carrierCode(query.result().normalizedFlightNumber()))
				.distinct().count()).isGreaterThanOrEqualTo(4);
		assertThat(queries.stream().map(query -> query.scenario().region()).collect(java.util.stream.Collectors.toSet()))
				.contains("EUROPE", "ASIA", "NORTH_AMERICA");
		assertThat(queries.stream().map(query -> query.scenario().locale()).collect(java.util.stream.Collectors.toSet()))
				.containsExactlyInAnyOrder("zh", "en", "es");
		assertThat(queries.stream().map(query -> query.scenario().hasConnection()).collect(java.util.stream.Collectors.toSet()))
				.containsExactlyInAnyOrder(true, false);
		assertThat(queries).allMatch(query -> query.result().live());
	}

	private static String airline(String flightNumber) {
		var resolution = AIRLINES.lookupIata(carrierCode(flightNumber));
		return resolution.uniqueCandidate().or(() -> resolution.candidates().stream()
				.filter(candidate -> candidate.name() != null)
				.filter(candidate -> !candidate.name().toLowerCase(Locale.ROOT).contains("cargo"))
				.findFirst()).map(AirlineRecord::name).orElse(carrierCode(flightNumber));
	}

	private static String carrierCode(String flightNumber) {
		Matcher iata = Pattern.compile("^([A-Z0-9]{2})(?=\\d)").matcher(flightNumber);
		if (iata.find()) return iata.group(1);
		Matcher icao = Pattern.compile("^([A-Z]{3})(?=\\d)").matcher(flightNumber);
		return icao.find() ? icao.group(1) : flightNumber;
	}

	private static List<String> nonBlank(String... values) {
		Set<String> result = new LinkedHashSet<>();
		for (String value : values) if (value != null && !value.isBlank()) result.add(value);
		return List.copyOf(result);
	}

	private static String lower(String value) {
		return value == null ? null : value.toLowerCase(Locale.ROOT);
	}

	private static boolean containsAny(String text, String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank() && text.contains(value)) return true;
		}
		return false;
	}

	private static void assertNoApiKeys(String output) {
		for (String name : List.of("SERPAPI_API_KEY", "AERODATABOX_RAPIDAPI_KEY",
				"AVIATIONSTACK_API_KEY", "VARIFLIGHT_API_KEY")) {
			String key = System.getenv(name);
			if (key != null && !key.isBlank()) assertThat(output).doesNotContain(key);
		}
	}

	private static List<Scenario> scenarios() {
		return List.of(
				new Scenario("BA269", "en", false, "EUROPE",
						"I am still at Heathrow and this flight has a problem. What should I do now?"),
				new Scenario("AF1440", "zh", true, "EUROPE",
						"我还要转机，现在担心赶不上下一班，应该怎么办？"),
				new Scenario("KL1001", "es", false, "EUROPE",
						"Sigo en Ámsterdam y tengo un problema con este vuelo. ¿Qué debo hacer ahora?"),
				new Scenario("CX255", "zh", true, "ASIA",
						"我到达后还要转机，现在应该做什么？"),
				new Scenario("SQ322", "en", true, "ASIA",
						"I have another flight after this one and I am worried about the connection. What should I do?"),
				new Scenario("AA100", "es", false, "NORTH_AMERICA",
						"Sigo en JFK y tengo un problema con este vuelo. ¿Qué debo hacer ahora?"),
				new Scenario("MF8501", "zh", false, "ASIA",
						"我还在厦门机场，这段航程出了问题，现在怎么办？"),
				new Scenario("IB717", "es", true, "EUROPE",
						"Tengo una conexión después de este vuelo. ¿Qué debo hacer ahora?"));
	}

	private record Scenario(String flightNumber, String locale, boolean hasConnection,
			String region, String passengerStatement) { }

	private record QueryCapture(Scenario scenario, ResolveFlightRequest query,
			ResolvedFlightContext result, List<Map<String, Object>> queryAttempts) { }
}
