package com.selene.onestepflight.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

@Component
public class JourneyProblemAnswerExtractor {
	private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[([^]\\n]+)]\\([^)]*\\)");
	private static final Pattern BARE_URL = Pattern.compile("(?i)https?://\\S+|www\\.\\S+");
	private static final Pattern CITATION = Pattern.compile("\\[(?:\\d+|[a-z])(?:\\s*[,，-]\\s*\\d+)*]", Pattern.CASE_INSENSITIVE);
	private static final Pattern SENTENCE = Pattern.compile("[^。！？.!?]+[。！？.!?]+(?:[”’\"']+)?");
	private static final List<String> STAFF_CONFIRMATIONS = List.of(
			"请与航空公司工作人员确认。",
			"Please confirm with airline staff.",
			"Confírmalo con el personal de la aerolínea.");

	public Optional<String> extract(JsonNode root) {
		return clean(rawText(root).orElse(null));
	}

	public Optional<String> rawText(JsonNode root) {
		if (root == null || root.isMissingNode() || root.isNull()) return Optional.empty();
		String raw = text(root.path("answer"));
		if (raw == null) raw = textBlocks(root.path("text_blocks"));
		if (raw == null) raw = text(root.path("reconstructed_markdown"));
		return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw.trim());
	}

	Optional<String> clean(String raw) {
		if (raw == null || raw.isBlank()) return Optional.empty();
		String value = MARKDOWN_LINK.matcher(raw).replaceAll("$1");
		value = BARE_URL.matcher(value).replaceAll(" ");
		value = CITATION.matcher(value).replaceAll(" ");
		value = value.replace("```", " ")
				.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
				.replaceAll("(?m)^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+|>\\s*)", "");
		for (String confirmation : STAFF_CONFIRMATIONS) value = value.replace(confirmation, " ");
		value = value.replaceAll("\\s+", " ").trim();
		if (value.isEmpty()) return Optional.empty();

		Matcher matcher = SENTENCE.matcher(value);
		List<String> sentences = new ArrayList<>();
		while (matcher.find() && sentences.size() < 2) {
			String sentence = matcher.group().trim();
			if (!sentence.isEmpty()) sentences.add(sentence);
		}
		return Optional.of(sentences.isEmpty() ? value : String.join(" ", sentences).trim());
	}

	private static String textBlocks(JsonNode blocks) {
		if (!blocks.isArray()) return null;
		List<String> values = new ArrayList<>();
		for (JsonNode block : blocks) {
			String value = text(block.path("paragraph"));
			if (value == null) value = firstText(block, "snippet", "text", "content");
			if (value != null) values.add(value);
		}
		return values.isEmpty() ? null : String.join("\n", values);
	}

	private static String firstText(JsonNode node, String... fields) {
		for (String field : fields) {
			String value = text(node.path(field));
			if (value != null) return value;
		}
		return null;
	}

	private static String text(JsonNode node) {
		return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText().trim() : null;
	}
}
