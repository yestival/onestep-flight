package com.selene.onestepflight.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Safely extracts source-bound URL candidates from both JSON-shaped and natural-language AI Mode responses. */
public final class AiResponseCandidateExtractor {
	private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^]\\r\\n]+)]\\((https://[^\\s)]+)\\)");
	private final ObjectMapper mapper;

	public AiResponseCandidateExtractor(ObjectMapper mapper) { this.mapper = mapper; }

	public Result extract(JsonNode root) {
		List<String> diagnostics = new ArrayList<>();
		StrictJson strict = strictJson(root);
		LinkedHashMap<String, MutableCandidate> candidates = new LinkedHashMap<>();
		Map<String, ReferenceInfo> references = collectReferences(root, candidates, diagnostics);
		collectSnippetLinks(root == null ? null : root.path("text_blocks"), "$.text_blocks", null,
				references, candidates, diagnostics);
		collectMarkdown(root, references, candidates, diagnostics);

		if (!strict.parsed() && !candidates.isEmpty()) diagnostics.add("AI_NON_JSON_REFERENCES_PARSED");
		if (!strict.parsed() && candidates.isEmpty()) diagnostics.add("AI_RESPONSE_NO_EXTRACTABLE_REFERENCES");
		ResponseMode mode = strict.parsed() ? ResponseMode.STRICT_JSON
				: candidates.isEmpty() ? ResponseMode.NO_EXTRACTABLE_CONTENT : ResponseMode.NATURAL_LANGUAGE;
		List<Candidate> immutable = candidates.values().stream().map(MutableCandidate::freeze).toList();
		Map<ParseMode, Integer> counts = new EnumMap<>(ParseMode.class);
		for (Candidate candidate : immutable) {
			for (Evidence evidence : candidate.evidence()) counts.merge(evidence.parseMode(), 1, Integer::sum);
		}
		return new Result(strict.payload(), mode, strict.attempted(), strict.parsed(), strict.failure(),
				immutable, Map.copyOf(counts), List.copyOf(new LinkedHashSet<>(diagnostics)));
	}

	private StrictJson strictJson(JsonNode root) {
		if (root == null || root.isNull() || root.isMissingNode()) {
			return new StrictJson(null, false, false, "AI_RESPONSE_MISSING");
		}
		List<String> values = new ArrayList<>();
		for (String field : List.of("answer", "reconstructed_markdown", "text")) addText(values, root.path(field));
		collectPotentialJsonText(root.path("text_blocks"), values);
		for (String value : values) {
			String candidate = stripJsonFence(value);
			try {
				JsonNode parsed = mapper.readTree(candidate);
				if (parsed != null && parsed.isObject()) return new StrictJson(parsed, true, true, null);
			} catch (RuntimeException ignored) { }
		}
		return new StrictJson(null, !values.isEmpty(), false,
				values.isEmpty() ? "AI_RESPONSE_HAS_NO_TEXT" : "STRICT_JSON_OBJECT_NOT_FOUND");
	}

	private static void collectPotentialJsonText(JsonNode node, List<String> values) {
		if (node == null || node.isNull() || node.isMissingNode()) return;
		if (node.isArray()) {
			for (JsonNode item : node) collectPotentialJsonText(item, values);
			return;
		}
		if (!node.isObject()) return;
		for (String field : List.of("snippet", "text", "content")) addText(values, node.path(field));
		node.properties().forEach(entry -> {
			if (entry.getValue().isObject() || entry.getValue().isArray()) {
				collectPotentialJsonText(entry.getValue(), values);
			}
		});
	}

	private Map<String, ReferenceInfo> collectReferences(JsonNode root,
			Map<String, MutableCandidate> candidates, List<String> diagnostics) {
		Map<String, ReferenceInfo> references = new LinkedHashMap<>();
		JsonNode values = root == null ? null : root.path("references");
		if (values == null || !values.isArray()) return references;
		int index = 0;
		for (JsonNode node : values) {
			String path = "$.references[" + index++ + "]";
			String rawUrl = firstNonBlank(text(node, "link"), text(node, "url"));
			String normalized = normalizeUrl(rawUrl);
			if (normalized == null) continue;
			ReferenceInfo reference = new ReferenceInfo(rawUrl, normalized, text(node, "title"),
					firstNonBlank(text(node, "snippet"), text(node, "description")), path);
			references.putIfAbsent(normalized, reference);
			add(candidates, reference, new Evidence(ParseMode.REFERENCE, path, reference.title(),
					reference.title(), reference.snippet(), reference.title(), reference.snippet(), path), diagnostics);
		}
		return references;
	}

	private void collectSnippetLinks(JsonNode node, String path, String inheritedContext,
			Map<String, ReferenceInfo> references, Map<String, MutableCandidate> candidates,
			List<String> diagnostics) {
		if (node == null || node.isNull() || node.isMissingNode()) return;
		if (node.isArray()) {
			int index = 0;
			for (JsonNode item : node) collectSnippetLinks(item, path + "[" + index++ + "]",
					inheritedContext, references, candidates, diagnostics);
			return;
		}
		if (!node.isObject()) return;
		String context = firstNonBlank(text(node, "snippet"), text(node, "text"), text(node, "content"),
				text(node, "description"), inheritedContext);
		JsonNode links = node.path("snippet_links");
		if (links.isArray()) {
			int index = 0;
			for (JsonNode link : links) {
				String linkPath = path + ".snippet_links[" + index++ + "]";
				String rawUrl = firstNonBlank(text(link, "link"), text(link, "url"));
				String normalized = normalizeUrl(rawUrl);
				if (normalized == null) continue;
				ReferenceInfo reference = references.get(normalized);
				ReferenceInfo source = reference == null
						? new ReferenceInfo(rawUrl, normalized, null, null, linkPath) : reference;
				add(candidates, source, new Evidence(ParseMode.SNIPPET_LINK, linkPath,
						firstNonBlank(text(link, "title"), reference == null ? null : reference.title()),
						firstNonBlank(text(link, "text"), text(link, "title")), context,
						reference == null ? null : reference.title(), reference == null ? null : reference.snippet(),
						reference == null ? linkPath : reference.sourceJsonPath()), diagnostics);
			}
		}
		node.properties().forEach(entry -> {
			if (!"snippet_links".equals(entry.getKey())
					&& (entry.getValue().isObject() || entry.getValue().isArray())) {
				collectSnippetLinks(entry.getValue(), path + "." + entry.getKey(), context,
						references, candidates, diagnostics);
			}
		});
	}

	private void collectMarkdown(JsonNode root, Map<String, ReferenceInfo> references,
			Map<String, MutableCandidate> candidates, List<String> diagnostics) {
		String markdown = root == null ? null : text(root, "reconstructed_markdown");
		if (markdown == null) return;
		Matcher matcher = MARKDOWN_LINK.matcher(markdown);
		int index = 0;
		while (matcher.find()) {
			String linkText = matcher.group(1).replace("\\-", "-").trim();
			String rawUrl = matcher.group(2).replace("\\-", "-").trim();
			String normalized = normalizeUrl(rawUrl);
			ReferenceInfo reference = normalized == null ? null : references.get(normalized);
			if (reference == null && normalized != null) {
				MutableCandidate structurallyBound = candidates.get(normalized);
				if (structurallyBound != null && !structurallyBound.evidence.isEmpty()) {
					Evidence binding = structurallyBound.evidence.get(0);
					reference = new ReferenceInfo(structurallyBound.rawUrl, structurallyBound.normalizedUrl,
							firstNonBlank(binding.title(), binding.referenceTitle()),
							firstNonBlank(binding.context(), binding.referenceSnippet()),
							binding.bindingPath());
				}
			}
			String path = "$.reconstructed_markdown.links[" + index++ + "]";
			if (reference == null) {
				diagnostics.add("AI_MARKDOWN_URL_NOT_STRUCTURALLY_BOUND");
				continue;
			}
			add(candidates, reference, new Evidence(ParseMode.MARKDOWN_LINK, path, reference.title(),
					linkText, surrounding(markdown, matcher.start(), matcher.end()), reference.title(),
					reference.snippet(), reference.sourceJsonPath()), diagnostics);
		}
	}

	private static void add(Map<String, MutableCandidate> candidates, ReferenceInfo source,
			Evidence evidence, List<String> diagnostics) {
		MutableCandidate candidate = candidates.get(source.normalizedUrl());
		if (candidate == null) {
			candidate = new MutableCandidate(source.rawUrl(), source.normalizedUrl());
			candidates.put(source.normalizedUrl(), candidate);
		} else {
			diagnostics.add("AI_CANDIDATE_DUPLICATE");
		}
		candidate.evidence.add(evidence);
	}

	public static String normalizeUrl(String value) {
		URI uri = OfficialTravelSourcePolicy.safeUri(value);
		if (uri == null) return null;
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		String path = uri.normalize().getRawPath();
		if (path == null || path.isBlank()) path = "/";
		if (path.length() > 1) path = path.replaceFirst("/+$", "");
		return "https://" + host + path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
	}

	private static String surrounding(String value, int start, int end) {
		int left = Math.max(0, start - 160);
		int right = Math.min(value.length(), end + 160);
		return value.substring(left, right).replaceAll("\\s+", " ").trim();
	}

	private static void addText(List<String> values, JsonNode node) {
		if (node != null && node.isTextual() && !node.asText().isBlank()) values.add(node.asText().trim());
	}

	private static String stripJsonFence(String value) {
		String trimmed = value.trim();
		if (trimmed.startsWith("```json") && trimmed.endsWith("```")) return trimmed.substring(7, trimmed.length() - 3).trim();
		if (trimmed.startsWith("```") && trimmed.endsWith("```")) return trimmed.substring(3, trimmed.length() - 3).trim();
		return trimmed;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.path(field);
		return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) if (value != null && !value.isBlank()) return value;
		return null;
	}

	public enum ParseMode { STRICT_JSON, REFERENCE, SNIPPET_LINK, MARKDOWN_LINK }
	public enum ResponseMode { STRICT_JSON, NATURAL_LANGUAGE, NO_EXTRACTABLE_CONTENT }

	public record Evidence(ParseMode parseMode, String sourceJsonPath, String title, String linkText,
			String context, String referenceTitle, String referenceSnippet, String bindingPath) { }
	public record Candidate(String rawUrl, String normalizedUrl, List<Evidence> evidence) {
		public Candidate { evidence = evidence == null ? List.of() : List.copyOf(evidence); }
	}
	public record Result(JsonNode strictJsonPayload, ResponseMode responseMode, boolean jsonParseAttempted,
			boolean jsonParsed, String jsonParseFailure, List<Candidate> candidates,
			Map<ParseMode, Integer> extractedByMode, List<String> diagnostics) {
		public Result {
			candidates = candidates == null ? List.of() : List.copyOf(candidates);
			extractedByMode = extractedByMode == null ? Map.of() : Map.copyOf(extractedByMode);
			diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
		}
		public int count(ParseMode mode) { return extractedByMode.getOrDefault(mode, 0); }
	}

	private record StrictJson(JsonNode payload, boolean attempted, boolean parsed, String failure) { }
	private record ReferenceInfo(String rawUrl, String normalizedUrl, String title, String snippet,
			String sourceJsonPath) { }
	private static final class MutableCandidate {
		private final String rawUrl;
		private final String normalizedUrl;
		private final List<Evidence> evidence = new ArrayList<>();
		private MutableCandidate(String rawUrl, String normalizedUrl) {
			this.rawUrl = rawUrl;
			this.normalizedUrl = normalizedUrl;
		}
		private Candidate freeze() { return new Candidate(rawUrl, normalizedUrl, List.copyOf(evidence)); }
	}
}
