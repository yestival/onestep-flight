package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.selene.onestepflight.dto.JourneyProblemRequest;
import com.selene.onestepflight.dto.JourneyProblemRequest.CurrentFlight;
import com.selene.onestepflight.dto.JourneyProblemRequest.FlightChangeInput;
import com.selene.onestepflight.dto.JourneyProblemResponse.AnswerStatus;
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
	private static final Pattern FORBIDDEN_FORMAT = Pattern.compile(
			"(?im)https?://|www\\.|^\\s{0,3}#{1,6}\\s+|^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)");
	private static final Pattern INTERNAL_ERROR = Pattern.compile(
			"(?i)\\b(?:exception|traceback|stack trace|internal server error|serpapi|aerodatabox|"
					+ "aviationstack|variflight|api[_ -]?key|java\\.lang\\.)\\b|\\bhttp\\s+[45]\\d{2}\\b");
	private static final String PENDING_HUMAN_REVIEW = "PENDING_HUMAN_REVIEW";
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
			try {
				result = resolvedFlightService.resolve(query);
				queryAttempts.add(queryAttempt(1, "SUCCESS", null, result.providerResults()));
			} catch (AllFlightProvidersUnavailableException exception) {
				queryAttempts.add(queryAttempt(1, "FAILED", exception.code(),
						exception.providerResults()));
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
						+ " did not return a live project flight result");
			}
			assertThat(result.live())
					.as("%s must be supplied by a live project flight query", scenario.flightNumber())
					.isTrue();
			assertThat(result.demo())
					.as("%s must not use demo flight data", scenario.flightNumber())
					.isFalse();
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
			List<String> technicalIssues = (List<String>) record.get("technicalIssues");
			assertThat(technicalIssues)
					.as("Stop on the first objective technical failure")
					.isEmpty();
		}

		assertThat(records).hasSize(8);
	}

	@Test
	void naturalLanguageWordingDoesNotCreateTechnicalFailures() {
		String kl1001Answer = "Comprendo su preocupación por la situación en Ámsterdam. "
				+ "Como este vuelo ya operó en una fecha anterior, le recomiendo que se acerque al mostrador "
				+ "de la aerolínea con los registros de su viaje para que el personal pueda confirmar el estado "
				+ "de su reserva y guiarle en los siguientes pasos.";
		String aa100Answer = "Comprendo su preocupación por la situación en el aeropuerto tras el vuelo "
				+ "programado para hace dos días. Le sugiero acudir al personal de American Airlines en la "
				+ "Terminal 8 con sus registros de viaje para que puedan verificar su caso y confirmarle los "
				+ "pasos a seguir con su itinerario.";
		String wordingOutsideAnyFiniteActionVocabulary =
				"El personal del aeropuerto puede orientarle sobre su itinerario.";

		assertThat(technicalIssues(validTechnicalRecord("KL1001", kl1001Answer))).isEmpty();
		assertThat(technicalIssues(validTechnicalRecord("AA100", aa100Answer))).isEmpty();
		assertThat(technicalIssues(validTechnicalRecord("TEST1", wordingOutsideAnyFiniteActionVocabulary)))
				.isEmpty();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> existingRecord(int run) throws Exception {
		Path path = OUTPUT.resolve("run-%02d.json".formatted(run));
		assertThat(path).exists();
		Map<String, Object> record = objectMapper.readValue(Files.readString(path), Map.class);
		if (!record.containsKey("flightQueryDemo")) {
			Map<String, Object> resolvedFlightContext =
					(Map<String, Object>) record.get("resolvedFlightContext");
			record.put("flightQueryDemo", resolvedFlightContext == null
					? null : resolvedFlightContext.get("demo"));
		}
		record.put("airline", airline((String) record.get("flightNumber")));
		applyTechnicalReview(record);
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
		record.put("flightQueryDemo", result.demo());
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
		applyTechnicalReview(record);
		return record;
	}

	private static void applyTechnicalReview(Map<String, Object> record) {
		for (String obsolete : List.of("manualAcceptance", "manualReviewStatus", "manualReviewNote",
				"manualReview", "acceptance", "review", "issues")) {
			record.remove(obsolete);
		}
		Map<String, Boolean> checks = technicalChecks(record);
		record.put("technicalChecks", checks);
		record.put("technicalIssues", failedTechnicalChecks(checks));
		record.put("semanticReviewStatus", PENDING_HUMAN_REVIEW);
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

	private static Map<String, Boolean> technicalChecks(Map<String, Object> record) {
		Map<String, Boolean> checks = new LinkedHashMap<>();
		checks.put("flightQuerySucceeded", querySucceeded(record.get("flightQueryAttempts")));
		checks.put("flightQueryLive", Boolean.TRUE.equals(record.get("flightQueryLive")));
		checks.put("flightQueryNotDemo", Boolean.FALSE.equals(record.get("flightQueryDemo")));
		checks.put("flightNumberMatchesQuery",
				nonBlankEquals(record.get("preferredFlightNumber"), record.get("flightNumber")));
		checks.put("tracePresent", hasEntries(record.get("attempts")));
		checks.put("promptPresent", nonBlank(record.get("actualPrimaryPrompt"))
				&& firstAttemptHasPrompt(record.get("attempts")));
		checks.put("finalStatusAnswer", AnswerStatus.ANSWER.name().equals(String.valueOf(record.get("finalStatus"))));
		checks.put("finalPathNotFallback", record.get("finalPath") != null
				&& !"FALLBACK".equals(String.valueOf(record.get("finalPath"))));
		String answer = record.get("finalDisplayBody") instanceof String value ? value : "";
		checks.put("answerNotBlank", !answer.isBlank());
		checks.put("atMostTwoSentences", sentenceCount(answer) <= 2);
		checks.put("noUrlTitleOrList", !FORBIDDEN_FORMAT.matcher(answer).find());
		checks.put("noInternalErrorInformation", !INTERNAL_ERROR.matcher(answer).find());
		return checks;
	}

	private static List<String> technicalIssues(Map<String, Object> record) {
		return failedTechnicalChecks(technicalChecks(record));
	}

	private static List<String> failedTechnicalChecks(Map<String, Boolean> checks) {
		return checks.entrySet().stream()
				.filter(entry -> !Boolean.TRUE.equals(entry.getValue()))
				.map(Map.Entry::getKey)
				.toList();
	}

	private static boolean querySucceeded(Object attemptsValue) {
		if (!(attemptsValue instanceof List<?> attempts) || attempts.isEmpty()) return false;
		Object last = attempts.get(attempts.size() - 1);
		return last instanceof Map<?, ?> attempt && "SUCCESS".equals(attempt.get("outcome"));
	}

	private static boolean hasEntries(Object value) {
		return value instanceof List<?> entries && !entries.isEmpty();
	}

	private static boolean firstAttemptHasPrompt(Object attemptsValue) {
		if (!(attemptsValue instanceof List<?> attempts) || attempts.isEmpty()) return false;
		Object first = attempts.get(0);
		return first instanceof Map<?, ?> attempt && nonBlank(attempt.get("prompt"));
	}

	private static boolean nonBlankEquals(Object expected, Object actual) {
		return nonBlank(expected) && String.valueOf(expected).equals(String.valueOf(actual));
	}

	private static boolean nonBlank(Object value) {
		return value instanceof String text && !text.isBlank();
	}

	private static Map<String, Object> validTechnicalRecord(String flightNumber, String answer) {
		Map<String, Object> queryAttempt = new LinkedHashMap<>();
		queryAttempt.put("outcome", "SUCCESS");
		Map<String, Object> answerAttempt = new LinkedHashMap<>();
		answerAttempt.put("prompt", "deterministic prompt fixture");
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("preferredFlightNumber", flightNumber);
		record.put("flightNumber", flightNumber);
		record.put("flightQueryAttempts", List.of(queryAttempt));
		record.put("flightQueryLive", true);
		record.put("flightQueryDemo", false);
		record.put("attempts", List.of(answerAttempt));
		record.put("actualPrimaryPrompt", "deterministic prompt fixture");
		record.put("finalStatus", AnswerStatus.ANSWER.name());
		record.put("finalPath", "PRIMARY");
		record.put("finalDisplayBody", answer);
		return record;
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
					.append("- Technical checks: ").append(record.get("technicalChecks")).append('\n')
					.append("- Technical issues: ").append(record.get("technicalIssues")).append('\n')
					.append("- Semantic review status: ").append(record.get("semanticReviewStatus"))
					.append("\n\n");
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
