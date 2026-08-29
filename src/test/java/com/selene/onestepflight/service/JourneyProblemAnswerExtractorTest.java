package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class JourneyProblemAnswerExtractorTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private final JourneyProblemAnswerExtractor extractor = new JourneyProblemAnswerExtractor();

	@Test void extractsAnswerAndLimitsItToTwoSentences() throws Exception {
		assertThat(extractor.extract(mapper.readTree(
				"{\"answer\":\"Please stay calm. Ask airline staff for the next step. This third sentence is removed.\"}")))
				.contains("Please stay calm. Ask airline staff for the next step.");
	}

	@Test void extractsTextBlocksInOrder() throws Exception {
		assertThat(extractor.extract(mapper.readTree("""
				{"text_blocks":[{"type":"paragraph","paragraph":"第一句。"},{"snippet":"第二句。"}]}
				"""))).contains("第一句。 第二句。");
	}

	@Test void cleansLinksListsCitationsAndRepeatedStaffConfirmation() throws Exception {
		assertThat(extractor.extract(mapper.readTree("""
				{"reconstructed_markdown":"## Advice\\n- Stay calm and keep [your pass](https://example.test). [1]\\n- Ask airline staff. Please confirm with airline staff."}
				"""))).contains("Advice Stay calm and keep your pass. Ask airline staff.");
	}

	@Test void acceptsOneNonEmptySentenceWithoutTerminalPunctuation() throws Exception {
		assertThat(extractor.extract(mapper.readTree("{\"answer\":\"Please keep your boarding pass\"}")))
				.contains("Please keep your boarding pass");
	}

	@Test void emptyBodyIsRejected() throws Exception {
		assertThat(extractor.extract(mapper.readTree("{}"))).isEmpty();
		assertThat(extractor.clean("   ")).isEmpty();
	}
}
