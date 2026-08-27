package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TravelDocumentDateParserTest {

	private static final Clock CLOCK = Clock.fixed(
			Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC);
	private final TravelDocumentDateParser parser = new TravelDocumentDateParser(CLOCK);

	@ParameterizedTest
	@ValueSource(strings = {
			"4AUG", "04AUG", "AUG4", "AUG04", "AUG 4", "4 AUG", "4-AUG", "4/AUG",
			"4.AUG", "4 Aug.", "04AUG26", "04AUG2026", "4-AUG-26", "4-AUG-2026",
			"4/AUG/2026", "4.AUG.2026", "4 August", "4 August 2026", "August 4",
			"August 4, 2026", "4th August 2026", "August 4th, 2026", "4 Aug '26",
			"August 4 '26", "2026 AUG 4", "2026 4 AUG", "4 agosto", "4 agosto 2026",
			"4 de agosto", "4 de agosto de 2026", "4 AGO", "04AGO26", "4-AGO-2026"
	})
	void parsesMonthNameAndCompactFormats(String value) {
		assertThat(parse(value, TravelDocumentDateParser.Source.STRUCTURED_DATE))
				.containsExactly(LocalDate.of(2026, 8, 4));
	}

	@Test
	void twoDigitYearsAlwaysUseTheTwentyFirstCentury() {
		assertThat(parse("04AUG26", TravelDocumentDateParser.Source.STRUCTURED_DATE))
				.containsExactly(LocalDate.of(2026, 8, 4));
		assertThat(parse("04AUG09", TravelDocumentDateParser.Source.STRUCTURED_DATE))
				.containsExactly(LocalDate.of(2009, 8, 4));
		assertThat(parse("04AUG99", TravelDocumentDateParser.Source.STRUCTURED_DATE))
				.containsExactly(LocalDate.of(2099, 8, 4));
		assertThat(parse("4AUG", TravelDocumentDateParser.Source.STRUCTURED_DATE))
				.containsExactly(LocalDate.of(2026, 8, 4));
	}

	@Test
	void numericDatesEnumerateOnlyTheFixedValidOrders() {
		assertThat(parse("2026-08-04", TravelDocumentDateParser.Source.STRUCTURED_DATE))
				.containsExactly(LocalDate.of(2026, 8, 4));
		assertThat(parse("2026/8/4", TravelDocumentDateParser.Source.STRUCTURED_DATE))
				.containsExactly(LocalDate.of(2026, 8, 4));
		assertThat(parse("2026.08.04", TravelDocumentDateParser.Source.STRUCTURED_DATE))
				.containsExactly(LocalDate.of(2026, 8, 4));
		assertThat(parse("13/08/2026", TravelDocumentDateParser.Source.STRUCTURED_DATE))
				.containsExactly(LocalDate.of(2026, 8, 13));
		assertThat(parse("08/13/2026", TravelDocumentDateParser.Source.STRUCTURED_DATE))
				.containsExactly(LocalDate.of(2026, 8, 13));
		assertThat(parse("04/08/2026", TravelDocumentDateParser.Source.STRUCTURED_DATE))
				.containsExactly(LocalDate.of(2026, 4, 8), LocalDate.of(2026, 8, 4));
		assertThat(parse("04/08", TravelDocumentDateParser.Source.STRUCTURED_DATE))
				.containsExactly(LocalDate.of(2026, 4, 8), LocalDate.of(2026, 8, 4));
	}

	@Test
	void compactNumericDatesAreRestrictedToStructuredFields() {
		assertThat(parse("20260804", TravelDocumentDateParser.Source.STRUCTURED_DATE))
				.containsExactly(LocalDate.of(2026, 8, 4));
		assertThat(parse("20260804", TravelDocumentDateParser.Source.FREE_TEXT)).isEmpty();
		assertThat(parse("040826", TravelDocumentDateParser.Source.STRUCTURED_DATE))
				.containsExactly(LocalDate.of(2004, 8, 26), LocalDate.of(2026, 4, 8),
						LocalDate.of(2026, 8, 4));
		assertThat(parse("040826", TravelDocumentDateParser.Source.FREE_TEXT)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"2026年8月4日", "2026年08月04日", "2026年8月4号", "2026年08月04号",
			"8月4日", "08月04日", "8月4号", "08月04号"
	})
	void parsesChineseGregorianFormats(String value) {
		assertThat(parse(value, TravelDocumentDateParser.Source.STRUCTURED_DATE))
				.containsExactly(LocalDate.of(2026, 8, 4));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"31FEB2026", "2026-02-30", "31/04/2026", "2026-13-04", "2026-00-04",
			"0AUG2026", "32AUG2026"
	})
	void rejectsInvalidDates(String value) {
		assertThat(parse(value, TravelDocumentDateParser.Source.STRUCTURED_DATE)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"123456", "20260804", "12345678", "781234567890", "AF1668", "18A", "£123.45"
	})
	void freeTextIdentifiersDoNotBecomeDates(String value) {
		assertThat(parse(value, TravelDocumentDateParser.Source.FREE_TEXT)).isEmpty();
	}

	@Test
	void freeTextUsesOnlyAdjacentMonthComponentsAndNeverTimeDigits() {
		assertThat(parse("5SEP OK T3 T2A", TravelDocumentDateParser.Source.FREE_TEXT))
				.containsExactly(LocalDate.of(2026, 9, 5));
		assertThat(parse("AB123 Economy 5SEP 09:25 10:00 5SEP OK T2",
				TravelDocumentDateParser.Source.FREE_TEXT))
				.containsExactly(LocalDate.of(2026, 9, 5));
		assertThat(parse("3 days ago", TravelDocumentDateParser.Source.FREE_TEXT)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "25 10", "09 25", "09 25 10", "09:25 10:00" })
	void freeTextDoesNotUseWhitespaceSeparatedNumbersAsDates(String value) {
		assertThat(parse(value, TravelDocumentDateParser.Source.FREE_TEXT)).isEmpty();
	}

	@Test
	void normalizationConvertsUnicodeDigitsAndPreservesTheRawValue() {
		String raw = "２０２６–０８–０４";
		TravelDocumentDateParser.ParseResult result = parser.parse(raw,
				TravelDocumentDateParser.Source.STRUCTURED_DATE);
		assertThat(result.rawValue()).isEqualTo(raw);
		assertThat(result.normalizedValue()).isEqualTo("2026-08-04");
		assertThat(result.candidates()).containsExactly(LocalDate.of(2026, 8, 4));
	}

	@Test
	void nonGregorianEraMarkersAreNotConvertedOrUsedForYearlessCompletion() {
		assertThat(parse("令和8年8月4日", TravelDocumentDateParser.Source.STRUCTURED_DATE)).isEmpty();
		assertThat(parse("令和注记 2026-08-04", TravelDocumentDateParser.Source.FREE_TEXT))
				.containsExactly(LocalDate.of(2026, 8, 4));
	}

	private List<LocalDate> parse(String value, TravelDocumentDateParser.Source source) {
		return parser.parse(value, source).candidates();
	}
}
