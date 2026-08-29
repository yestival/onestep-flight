package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class AiResponseCandidateExtractorTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private final AiResponseCandidateExtractor extractor = new AiResponseCandidateExtractor(mapper);

	@Test
	void strictJsonRemainsFirstClassWhileReferencesAreStillRecorded() {
		var root = mapper.readTree("""
				{
				  "text_blocks": [{"snippet": "{\\\"airportCode\\\":\\\"CDG\\\",\\\"airportConfirmed\\\":true,\\\"mapCandidates\\\":[]}"}],
				  "references": [{"link": "https://airport.example/maps?terminal=2E", "title": "Terminal map"}]
				}
				""");

		var result = extractor.extract(root);

		assertThat(result.responseMode()).isEqualTo(AiResponseCandidateExtractor.ResponseMode.STRICT_JSON);
		assertThat(result.jsonParsed()).isTrue();
		assertThat(result.strictJsonPayload().path("airportCode").asText()).isEqualTo("CDG");
		assertThat(result.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.normalizedUrl()).isEqualTo("https://airport.example/maps?terminal=2E");
			assertThat(candidate.evidence()).extracting(AiResponseCandidateExtractor.Evidence::parseMode)
					.containsExactly(AiResponseCandidateExtractor.ParseMode.REFERENCE);
		});
	}

	@Test
	void proseNestedLinksAndBoundMarkdownAreDeduplicatedWithoutTrustingFreeMarkdown() {
		var root = mapper.readTree("""
				{
				  "answer": "The official resources are listed below.",
				  "text_blocks": [{
				    "snippet": "Customer service: +33 1 70 36 39 50",
				    "items": [{"snippet_links": [{"link": "https://airport.example/contact?lang=en", "text": "Contact centre"}]}]
				  }],
				  "reconstructed_markdown": "[Contact centre](https://airport.example/contact?lang=en) and [unbound](https://evil.example/map)",
				  "references": [{"link": "http://airport.example/insecure", "title": "Ignored"}]
				}
				""");

		var result = extractor.extract(root);

		assertThat(result.responseMode()).isEqualTo(AiResponseCandidateExtractor.ResponseMode.NATURAL_LANGUAGE);
		assertThat(result.jsonParsed()).isFalse();
		assertThat(result.jsonParseFailure()).isEqualTo("STRICT_JSON_OBJECT_NOT_FOUND");
		assertThat(result.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.normalizedUrl()).isEqualTo("https://airport.example/contact?lang=en");
			assertThat(candidate.evidence()).extracting(AiResponseCandidateExtractor.Evidence::parseMode)
					.contains(AiResponseCandidateExtractor.ParseMode.SNIPPET_LINK,
							AiResponseCandidateExtractor.ParseMode.MARKDOWN_LINK);
		});
		assertThat(result.diagnostics()).contains("AI_NON_JSON_REFERENCES_PARSED",
				"AI_CANDIDATE_DUPLICATE", "AI_MARKDOWN_URL_NOT_STRUCTURALLY_BOUND");
		assertThat(result.candidates()).noneSatisfy(candidate ->
				assertThat(candidate.normalizedUrl()).contains("evil.example"));
	}

	@Test
	void proseWithoutExtractableStructuredSourcesHasExplicitModeAndDiagnostic() {
		var result = extractor.extract(mapper.readTree("{\"answer\":\"Try the airport website.\"}"));

		assertThat(result.responseMode())
				.isEqualTo(AiResponseCandidateExtractor.ResponseMode.NO_EXTRACTABLE_CONTENT);
		assertThat(result.candidates()).isEmpty();
		assertThat(result.diagnostics()).contains("AI_RESPONSE_NO_EXTRACTABLE_REFERENCES");
	}

	@Test
	void queryDifferentiatedTerminalMapsRemainDistinctCandidates() {
		var result = extractor.extract(mapper.readTree("""
				{
				  "references": [
				    {"link":"https://airport.example/map?terminal=1&floor=2","title":"T1 floor 2"},
				    {"link":"https://airport.example/map?terminal=2&floor=2","title":"T2 floor 2"}
				  ]
				}
				"""));

		assertThat(result.candidates()).extracting(AiResponseCandidateExtractor.Candidate::normalizedUrl)
				.containsExactly(
						"https://airport.example/map?terminal=1&floor=2",
						"https://airport.example/map?terminal=2&floor=2");
	}
}
