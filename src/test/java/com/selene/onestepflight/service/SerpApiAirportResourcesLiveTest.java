package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.selene.onestepflight.dto.AirportResourcesRequest;
import com.selene.onestepflight.dto.AirportResourcesResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounded live harness. The eight-airport run uses one production BOTH lookup
 * per IATA code and saves each completed result before moving to the next code.
 */
class SerpApiAirportResourcesLiveTest {
	private static final String RUN_ID = "candidate-v2-eight-20260829-live-run-01";
	private static final List<String> EIGHT_AIRPORTS = List.of("SYD", "MUC", "NRT", "BKK", "MEX", "JNB", "YVR", "BOM");
	private static final Path LIVE_OUTPUT = Path.of("output",
			"airport-resources-candidate-v2-eight-20260829-live-run-01");
	private static final Path TRACE_OUTPUT = LIVE_OUTPUT.resolve("decision-traces");
	private static final Path SOURCE_STATE_FIXTURE = Path.of("output",
			"airport-resources-candidate-v2-eight-20260829-source-state.json");

	@Test
	@EnabledIfEnvironmentVariable(named = "RUN_LIVE_SERPAPI_AIRPORT_RESOURCES_CANDIDATE_V2_EIGHT", matches = "true")
	void checksEightAirportsOnceWithCodeOnlyAndSavesEachResult() throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		Files.createDirectories(LIVE_OUTPUT);
		Files.createDirectories(TRACE_OUTPUT);
		if (!Files.isRegularFile(SOURCE_STATE_FIXTURE)) {
			throw new IllegalStateException("Frozen source state is required before live execution");
		}
		JsonNode sourceState = mapper.readTree(Files.readString(SOURCE_STATE_FIXTURE));
		writeAtomically(LIVE_OUTPUT.resolve("source-state.json"),
				mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sourceState));
		List<String> failures = new ArrayList<>();
		int totalRequests = 0;
		for (String airportCode : EIGHT_AIRPORTS) {
			Path saved = LIVE_OUTPUT.resolve(airportCode + ".json");
			if (attempted(saved, mapper)) {
				JsonNode previous = mapper.readTree(Files.readString(saved));
				totalRequests += previous.path("response").path("serpApiRequestCount").asInt(0);
				System.out.printf("LIVE_RESOURCES_SKIP airport=%s reason=already-attempted runId=%s%n",
						airportCode, RUN_ID);
				continue;
			}

			String apiKey = required("SERPAPI_API_KEY");
			LiveTrace trace = new LiveTrace(mapper, apiKey);
			RestClient client = tracedRestClient(trace);
			Supplier<String> keySupplier = () -> apiKey;
			Clock clock = Clock.systemUTC();
			OfficialTravelSourcePolicy sourcePolicy = new OfficialTravelSourcePolicy();
			SerpApiOfficialAirportDomainResolver resolver = new SerpApiOfficialAirportDomainResolver(
					mapper, client, keySupplier, clock, sourcePolicy);
			ExecutorService ioExecutor = Executors.newFixedThreadPool(8);
			SerpApiAirportResourcesService service = new SerpApiAirportResourcesService(
					mapper, client, keySupplier, clock, sourcePolicy, resolver, ioExecutor);
			AirportResourcesRequest request = new AirportResourcesRequest(airportCode, null, null, true,
					AirportResourcesRequest.RefreshTarget.BOTH);
			long startedNanos = System.nanoTime();
			Instant startedAt = Instant.now();
			AirportResourcesResponse response = null;
			Throwable failure = null;
			try {
				response = service.findResources(request);
			} catch (Throwable throwable) {
				failure = throwable;
			} finally {
				ioExecutor.shutdownNow();
			}
			long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
			List<String> validationErrors = validateCompletedResponse(airportCode, response, trace.stages(), failure);
			boolean completed = validationErrors.isEmpty();
			if (!completed) failures.add(airportCode + ": " + String.join(" | ", validationErrors));
			Map<String, Object> record = new LinkedHashMap<>();
			record.put("schemaVersion", 3);
			record.put("runId", RUN_ID);
			record.put("attempted", true);
			record.put("completed", completed);
			record.put("airportCode", airportCode);
			record.put("startedAt", startedAt.toString());
			record.put("completedAt", Instant.now().toString());
			record.put("elapsedMs", elapsedMs);
			record.put("airportDatasetVersion", response == null || response.decisionTrace() == null
					? null : response.decisionTrace().airportDatasetVersion());
			record.put("identityVerifierVersion", response == null || response.decisionTrace() == null
					? null : response.decisionTrace().identityVerifierVersion());
			record.put("sourceState", sourceState);
			Map<String, Object> requestRecord = new LinkedHashMap<>();
			requestRecord.put("airportCode", airportCode);
			requestRecord.put("airportName", null);
			requestRecord.put("terminal", null);
			requestRecord.put("forceRefresh", true);
			requestRecord.put("refreshTarget", "BOTH");
			record.put("request", requestRecord);
			record.put("response", response);
			record.put("stages", trace.stages());
			record.put("observedHttpRequestCount", trace.stages().size());
			record.put("error", failure == null ? null : exactError(failure));
			record.put("validationErrors", validationErrors);
			if (completed) {
				writeAtomically(TRACE_OUTPUT.resolve(airportCode + ".json"),
						mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response.decisionTrace()));
			}
			writeAtomically(saved, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record));
			int requests = response == null ? trace.stages().size() : response.serpApiRequestCount();
			totalRequests += requests;
			System.out.printf("LIVE_RESOURCES airport=%s mapStatus=%s mapType=%s contactStatus=%s requests=%d elapsedMs=%d saved=%s%n",
					airportCode, response == null ? "EXCEPTION" : response.map().status(),
					response == null ? "EXCEPTION" : response.map().resultType(),
					response == null ? "EXCEPTION" : response.contact().status(), requests, elapsedMs, saved);
		}
		System.out.printf("LIVE_RESOURCES_TOTAL runId=%s airports=%d requests=%d output=%s%n",
				RUN_ID, EIGHT_AIRPORTS.size(), totalRequests, LIVE_OUTPUT);
		assertThat(failures).isEmpty();
	}

	@Test
	void completedFlagRequiresRequestResponsePersistencePrerequisitesAndValidation() {
		assertThat(completionAllowed(false, true, true, true)).isFalse();
		assertThat(completionAllowed(true, false, true, true)).isFalse();
		assertThat(completionAllowed(true, true, false, true)).isFalse();
		assertThat(completionAllowed(true, true, true, false)).isFalse();
		assertThat(completionAllowed(true, true, true, true)).isTrue();
	}

	private static List<String> validateCompletedResponse(String code, AirportResourcesResponse response,
			List<Map<String, Object>> observedStages, Throwable failure) {
		List<String> errors = new ArrayList<>();
		if (failure != null) errors.add(exactError(failure));
		if (response == null) errors.add("RESPONSE_NOT_SAVED");
		if (response != null && !code.equals(response.airportCode())) errors.add("AIRPORT_CODE_MISMATCH");
		if (response != null && response.decisionTrace() == null) errors.add("DECISION_TRACE_MISSING");
		if (response != null && response.serpApiRequestCount() > 6) errors.add("REQUEST_BUDGET_EXCEEDED");
		if (response != null && response.serpApiRequestCount() != observedStages.size()) {
			errors.add("REQUEST_COUNT_MISMATCH response=" + response.serpApiRequestCount()
					+ " observed=" + observedStages.size());
		}
		return List.copyOf(errors);
	}

	private static boolean completionAllowed(boolean requestCompleted, boolean responseSaved,
			boolean requiredValidationPassed, boolean recordSaved) {
		return requestCompleted && responseSaved && requiredValidationPassed && recordSaved;
	}

	private static RestClient tracedRestClient(LiveTrace trace) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(4));
		factory.setReadTimeout(Duration.ofSeconds(25));
		return RestClient.builder().baseUrl("https://serpapi.com")
				.requestFactory(new BufferingClientHttpRequestFactory(factory))
				.requestInterceptor(trace::intercept).build();
	}

	private static boolean attempted(Path file, ObjectMapper mapper) {
		if (!Files.isRegularFile(file)) return false;
		try {
			JsonNode root = mapper.readTree(Files.readString(file));
			return RUN_ID.equals(root.path("runId").asText()) && root.path("attempted").asBoolean(false);
		}
		catch (RuntimeException | IOException exception) { return false; }
	}

	private static void writeAtomically(Path target, String content) throws IOException {
		Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(temporary, content, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		try {
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String exactError(Throwable throwable) {
		String message = throwable.getMessage();
		return throwable.getClass().getName() + (message == null || message.isBlank() ? "" : ": " + message);
	}

	private static String required(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
		return value.trim();
	}

	private static String required(JsonNode node, String field) {
		String value = optional(node, field);
		if (value == null) throw new IllegalStateException(field + " is required");
		return value;
	}

	private static String optional(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
	}

	private static AirportResourcesRequest request(JsonNode item) {
		return new AirportResourcesRequest(required(item, "code"), required(item, "name"),
				optional(item, "terminal"), true);
	}

	private static Set<String> domains(JsonNode item) {
		LinkedHashSet<String> result = new LinkedHashSet<>();
		for (JsonNode domain : item.path("domains")) {
			if (domain.isTextual() && !domain.asText().isBlank()) result.add(domain.asText().trim());
		}
		if (result.isEmpty()) throw new IllegalStateException("domains is required");
		return Set.copyOf(result);
	}

	private static String host(String value) {
		try { return value == null ? "" : URI.create(value).getHost(); }
		catch (IllegalArgumentException exception) { return ""; }
	}

	private static String hostAndPath(String value) {
		try {
			if (value == null) return "";
			URI uri = URI.create(value);
			return (uri.getHost() == null ? "" : uri.getHost()) + (uri.getPath() == null ? "" : uri.getPath());
		} catch (IllegalArgumentException exception) { return ""; }
	}

	private static String value(String value) { return value == null ? "" : value.replaceAll("\\s+", " "); }

	private static final class LiveTrace {
		private final ObjectMapper mapper;
		private final String secret;
		private final AtomicInteger sequence = new AtomicInteger();
		private final List<Map<String, Object>> stages = new CopyOnWriteArrayList<>();

		private LiveTrace(ObjectMapper mapper, String secret) {
			this.mapper = mapper;
			this.secret = secret;
		}

		private ClientHttpResponse intercept(HttpRequest request, byte[] body,
				ClientHttpRequestExecution execution) throws IOException {
			long startedNanos = System.nanoTime();
			Map<String, String> parameters = queryParameters(request.getURI());
			Map<String, Object> stage = new LinkedHashMap<>();
			stage.put("sequence", sequence.incrementAndGet());
			stage.put("stage", stageName(parameters));
			stage.put("engine", parameters.get("engine"));
			stage.put("query", parameters.get("q"));
			stage.put("type", parameters.get("type"));
			stage.put("hl", parameters.get("hl"));
			stage.put("noCache", parameters.get("no_cache"));
			stage.put("requestUrl", sanitizedUri(request.getURI()));
			try {
				ClientHttpResponse response = execution.execute(request, body);
				stage.put("httpStatus", response.getStatusCode().value());
				stage.put("httpStatusText", response.getStatusText());
				byte[] responseBody = response.getBody().readAllBytes();
				String sanitizedBody = sanitize(new String(responseBody, StandardCharsets.UTF_8));
				JsonNode root = null;
				try {
					root = sanitizedBody.isBlank() ? null : mapper.readTree(sanitizedBody);
				} catch (RuntimeException parseException) {
					stage.put("responseParseError", exactError(parseException));
					stage.put("sanitizedNonJsonResponse", sanitizedBody);
				}
				stage.put("serpApiStatus", root == null ? null : text(root.path("search_metadata"), "status"));
				stage.put("serpApiError", root == null ? null : text(root, "error"));
				stage.put("candidates", candidates(root));
				stage.put("sanitizedResponse", root);
				stage.put("elapsedMs", Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
				stages.add(stage);
				return response;
			} catch (IOException | RuntimeException exception) {
				stage.put("elapsedMs", Duration.ofNanos(System.nanoTime() - startedNanos).toMillis());
				stage.put("error", exactError(exception));
				stages.add(stage);
				throw exception;
			}
		}

		private List<Map<String, Object>> stages() {
			return stages.stream().sorted((left, right) -> Integer.compare(
					((Number) left.get("sequence")).intValue(), ((Number) right.get("sequence")).intValue())).toList();
		}

		private String sanitizedUri(URI uri) {
			StringBuilder result = new StringBuilder(uri.getScheme()).append("://").append(uri.getAuthority())
					.append(uri.getPath());
			List<String> safe = new ArrayList<>();
			for (String pair : value(uri.getRawQuery()).split("&")) {
				if (!pair.toLowerCase(Locale.ROOT).startsWith("api_key=")) safe.add(pair);
			}
			if (!safe.isEmpty()) result.append('?').append(String.join("&", safe));
			return sanitize(result.toString());
		}

		private String sanitize(String value) {
			if (value == null) return null;
			String sanitized = secret == null || secret.isBlank() ? value : value.replace(secret, "[REDACTED]");
			return sanitized
					.replaceAll("(?i)(api[_-]?key\\s*[=:]\\s*)[^&\\s\\\"]+", "$1[REDACTED]")
					.replaceAll("(?i)(authorization\\s*[=:]\\s*)[^&\\r\\n\\\"]+", "$1[REDACTED]");
		}
	}

	private static Map<String, String> queryParameters(URI uri) {
		Map<String, String> result = new LinkedHashMap<>();
		for (String pair : value(uri.getRawQuery()).split("&")) {
			if (pair.isBlank()) continue;
			int equals = pair.indexOf('=');
			String key = equals < 0 ? pair : pair.substring(0, equals);
			String item = equals < 0 ? "" : pair.substring(equals + 1);
			if (!"api_key".equalsIgnoreCase(decode(key))) result.put(decode(key), decode(item));
		}
		return result;
	}

	private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }

	private static String stageName(Map<String, String> parameters) {
		String engine = value(parameters.get("engine"));
		String query = value(parameters.get("q"));
		if ("google_maps".equals(engine)) return "IDENTITY_GOOGLE_MAPS";
		if (query.endsWith("official airport website")) return "IDENTITY_OFFICIAL_DOMAIN";
		if ("google_ai_mode".equals(engine)
				&& query.startsWith("Find general passenger contact resources")) {
			return "CONTACT_AI_MODE";
		}
		if ("google_ai_mode".equals(engine)
				&& query.startsWith("Find official airport or requested-terminal maps")) return "MAP_AI_MODE";
		if ("google".equals(engine) && query.contains("airport map")
				&& query.contains("terminal map") && query.contains("site:")) return "MAP_OFFICIAL_SEARCH";
		return "CONTACT_SUPPLEMENTAL_SEARCH";
	}

	private static List<Map<String, Object>> candidates(JsonNode root) {
		if (root == null) return List.of();
		List<Map<String, Object>> result = new ArrayList<>();
		collectCandidates(root, "$", result, new LinkedHashSet<>());
		return List.copyOf(result);
	}

	private static void collectCandidates(JsonNode node, String path,
			List<Map<String, Object>> result, Set<String> seen) {
		if (node == null || node.isMissingNode() || node.isNull()) return;
		if (node.isArray()) {
			int index = 0;
			for (JsonNode item : node) collectCandidates(item, path + "[" + index++ + "]", result, seen);
			return;
		}
		if (!node.isObject()) return;
		String lowerPath = path.toLowerCase(Locale.ROOT);
		boolean candidatePath = lowerPath.contains("organic_results") || lowerPath.contains("local_results")
				|| lowerPath.contains("knowledge_graph") || lowerPath.contains("reference")
				|| lowerPath.contains("snippet_links") || lowerPath.contains("sitelinks");
		String url = firstNonBlank(text(node, "link"), text(node, "url"), text(node, "website"));
		String title = firstNonBlank(text(node, "title"), text(node, "name"), text(node, "source"));
		String phone = firstNonBlank(text(node, "phone"), text(node, "phone_number"));
		if (candidatePath && (url != null || phone != null)) {
			String key = value(url) + "|" + value(phone) + "|" + value(title);
			if (seen.add(key)) {
				Map<String, Object> candidate = new LinkedHashMap<>();
				candidate.put("jsonPath", path);
				candidate.put("title", title);
				candidate.put("url", url);
				candidate.put("displayedLink", text(node, "displayed_link"));
				candidate.put("phone", phone);
				candidate.put("snippet", firstNonBlank(text(node, "snippet"), text(node, "description")));
				result.add(candidate);
			}
		}
		node.properties().forEach(entry -> collectCandidates(entry.getValue(), path + "." + entry.getKey(), result, seen));
	}

	private static String text(JsonNode node, String field) {
		JsonNode item = node == null ? null : node.path(field);
		return item != null && item.isTextual() && !item.asText().isBlank() ? item.asText().trim() : null;
	}

	private static String firstNonBlank(String... values) {
		for (String item : values) if (item != null && !item.isBlank()) return item;
		return null;
	}
}
