package com.selene.onestepflight.service;

import java.text.DateFormatSymbols;
import java.text.Normalizer;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic Gregorian date parser for dates printed on travel documents. */
public final class TravelDocumentDateParser {

	public enum Source {
		STRUCTURED_DATE,
		FREE_TEXT
	}

	public record ParseResult(String rawValue, String normalizedValue,
			List<LocalDate> candidates, boolean explicitYear) {
		public ParseResult {
			candidates = candidates == null ? List.of() : List.copyOf(candidates);
		}

		public boolean hasCandidates() {
			return !candidates.isEmpty();
		}

		public boolean ambiguous() {
			return candidates.size() > 1;
		}

		public LocalDate uniqueDate() {
			return candidates.size() == 1 ? candidates.get(0) : null;
		}
	}

	public record DateMatch(int start, int end, String rawValue, String normalizedValue,
			List<LocalDate> candidates, boolean explicitYear) {
		public DateMatch {
			candidates = candidates == null ? List.of() : List.copyOf(candidates);
		}
	}

	private record PendingMatch(int start, int end, Set<LocalDate> candidates,
			boolean explicitYear) {
	}

	private record MonthPending(PendingMatch match, int aliasLength) {
	}

	private static final Pattern SEPARATED_TRIPLE = Pattern.compile(
			"(?<![\\p{L}\\p{N}])(?<a>\\d{1,4})\\s*([/.-])\\s*(?<b>\\d{1,2})\\s*\\2\\s*(?<c>\\d{1,4})(?![\\p{L}\\p{N}])");
	private static final Pattern SPACED_TRIPLE = Pattern.compile(
			"(?<![\\p{L}\\p{N}])(?<a>\\d{1,4})\\s+(?<b>\\d{1,2})\\s+(?<c>\\d{1,4})(?![\\p{L}\\p{N}])");
	private static final Pattern SEPARATED_PAIR = Pattern.compile(
			"(?<![\\p{L}\\p{N}])(?<a>\\d{1,2})\\s*([/.-])\\s*(?<b>\\d{1,2})(?![\\p{L}\\p{N}])");
	private static final Pattern SPACED_PAIR = Pattern.compile(
			"(?<![\\p{L}\\p{N}])(?<a>\\d{1,2})\\s+(?<b>\\d{1,2})(?![\\p{L}\\p{N}])");
	private static final Pattern CJK_FULL = Pattern.compile(
			"(?<!\\d)(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号](?!\\d)");
	private static final Pattern CJK_SHORT = Pattern.compile(
			"(?<![\\d年])(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号](?!\\d)");
	private static final Pattern NON_GREGORIAN_MARKER = Pattern.compile(
			"(?iu)(令和|平成|昭和|大正|明治|พ\\s*\\.?\\s*ศ\\s*\\.?|ฮิจเราะห์|هجري|هجرية)");
	private static final String ORDINAL_SUFFIX = "(?:st|nd|rd|th|º|ª|\\.)?";
	private static final String ORDINARY_CONNECTOR = "(?:\\s*[-/.]\\s*|\\s*)";
	private static final Pattern DAY_BEFORE_MONTH = Pattern.compile(
			"(?iu)(?<![:\\d])(?<day>\\d{1,2})(?![:\\d])" + ORDINAL_SUFFIX
					+ "(?<connector>" + ORDINARY_CONNECTOR + "|\\s+de\\s+)$");
	private static final Pattern DAY_AFTER_MONTH = Pattern.compile(
			"(?iu)^(?<connector>" + ORDINARY_CONNECTOR + "|\\s+de\\s+)"
					+ "(?<day>\\d{1,2})(?![:\\d])" + ORDINAL_SUFFIX);
	private static final Pattern YEAR_AFTER = Pattern.compile(
			"(?iu)^(?<connector>\\s*[-/.,']\\s*|\\s+de\\s+|\\s*)"
					+ "(?<year>\\d{2}|\\d{4})(?![:\\d])");
	private static final Pattern YEAR_BEFORE = Pattern.compile(
			"(?iu)(?<![:\\d])(?<year>\\d{2}|\\d{4})(?![:\\d])(?:\\s*[-/.,]\\s*|\\s+)$");

	private static final Map<String, Set<Integer>> MONTHS = buildMonthRegistry();
	private static final Set<String> SPANISH_MONTH_ALIASES = buildMonthAliases(
			Locale.forLanguageTag("es"));
	private static final List<String> MONTH_ALIASES = MONTHS.keySet().stream()
			.filter(TravelDocumentDateParser::usableMonthAlias)
			.sorted(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo))
			.toList();

	private final Clock clock;

	public TravelDocumentDateParser(Clock clock) {
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public ParseResult parse(String value, Source source) {
		String raw = value;
		String normalized = normalize(value);
		if (normalized == null || normalized.isBlank()) {
			return new ParseResult(raw, normalized, List.of(), false);
		}
		List<DateMatch> matches = findDatesInternal(raw, normalized, source);
		if (NON_GREGORIAN_MARKER.matcher(normalized).find()) {
			matches = matches.stream().filter(DateMatch::explicitYear).toList();
		}
		TreeSet<LocalDate> candidates = new TreeSet<>();
		boolean explicitYear = false;
		for (DateMatch match : matches) {
			candidates.addAll(match.candidates());
			explicitYear |= match.explicitYear();
		}
		return new ParseResult(raw, normalized, List.copyOf(candidates), explicitYear);
	}

	public List<DateMatch> findDates(String value, Source source) {
		String normalized = normalize(value);
		if (normalized == null || normalized.isBlank()) {
			return List.of();
		}
		List<DateMatch> matches = findDatesInternal(value, normalized, source);
		return NON_GREGORIAN_MARKER.matcher(normalized).find()
				? matches.stream().filter(DateMatch::explicitYear).toList() : matches;
	}

	public static String normalize(String value) {
		if (value == null) return null;
		String nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC);
		StringBuilder converted = new StringBuilder(nfkc.length());
		nfkc.codePoints().forEach(codePoint -> {
			int type = Character.getType(codePoint);
			if (type == Character.DECIMAL_DIGIT_NUMBER) {
				converted.append(Character.digit(codePoint, 10));
			} else if (codePoint == 0x00a0 || codePoint == '\n' || codePoint == '\r' || codePoint == '\t') {
				converted.append(' ');
			} else if (type == Character.DASH_PUNCTUATION) {
				converted.append('-');
			} else {
				converted.appendCodePoint(codePoint);
			}
		});
		return converted.toString().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
	}

	private List<DateMatch> findDatesInternal(String raw, String normalized, Source source) {
		List<PendingMatch> pending = new ArrayList<>();
		addCjkMatches(pending, normalized);
		addMonthNameMatches(pending, normalized);
		addNumericMatches(pending, normalized, source);
		if (source == Source.STRUCTURED_DATE) addCompactMatches(pending, normalized);

		Map<String, PendingMatch> distinct = new LinkedHashMap<>();
		for (PendingMatch match : pending) {
			if (match.candidates().isEmpty()) continue;
			String key = match.start() + "|" + match.end() + "|" + match.candidates();
			distinct.putIfAbsent(key, match);
		}
		List<DateMatch> matches = new ArrayList<>();
		for (PendingMatch match : distinct.values()) {
			int start = Math.max(0, Math.min(match.start(), normalized.length()));
			int end = Math.max(start, Math.min(match.end(), normalized.length()));
			String rawSlice = rawSlice(raw, normalized, start, end);
			matches.add(new DateMatch(start, end, rawSlice, normalized.substring(start, end),
					List.copyOf(new TreeSet<>(match.candidates())), match.explicitYear()));
		}
		matches.sort(Comparator.comparingInt(DateMatch::start).thenComparingInt(DateMatch::end));
		return List.copyOf(matches);
	}

	private void addCjkMatches(List<PendingMatch> destination, String text) {
		addFixedDateMatches(destination, text, CJK_FULL, true);
		addMonthDayMatches(destination, text, CJK_SHORT);
	}

	private void addFixedDateMatches(List<PendingMatch> destination, String text,
			Pattern pattern, boolean explicitYear) {
		Matcher matcher = pattern.matcher(text);
		while (matcher.find()) {
			Set<LocalDate> dates = new LinkedHashSet<>();
			addDate(dates, integer(matcher.group(1)), integer(matcher.group(2)), integer(matcher.group(3)));
			destination.add(new PendingMatch(matcher.start(), matcher.end(), dates, explicitYear));
		}
	}

	private void addMonthDayMatches(List<PendingMatch> destination, String text, Pattern pattern) {
		Matcher matcher = pattern.matcher(text);
		while (matcher.find()) {
			Set<LocalDate> dates = new LinkedHashSet<>();
			addDate(dates, Year.now(clock).getValue(), integer(matcher.group(1)), integer(matcher.group(2)));
			destination.add(new PendingMatch(matcher.start(), matcher.end(), dates, false));
		}
	}

	private void addMonthNameMatches(List<PendingMatch> destination, String text) {
		String foldedText = foldAccents(text);
		Set<String> seenAliasPositions = new LinkedHashSet<>();
		List<MonthPending> matches = new ArrayList<>();
		for (String alias : MONTH_ALIASES) {
			int from = 0;
			while (from <= foldedText.length() - alias.length()) {
				int start = foldedText.indexOf(alias, from);
				if (start < 0) break;
				int end = start + alias.length();
				from = start + 1;
				if (!monthBoundary(foldedText, start, end)) continue;
				if (alias.codePoints().filter(Character::isLetter).count() == 1
						&& (start > 0 && Character.isDigit(foldedText.codePointBefore(start))
								|| end < foldedText.length() && Character.isDigit(foldedText.codePointAt(end)))) continue;
				String positionKey = start + "|" + end;
				if (!seenAliasPositions.add(positionKey)) continue;
				Set<Integer> months = MONTHS.get(alias);
				if (months == null || months.isEmpty()) continue;
				PendingMatch match = monthNameMatch(text, start, end, months,
						SPANISH_MONTH_ALIASES.contains(alias));
				if (match != null) matches.add(new MonthPending(match, alias.length()));
			}
		}
		Map<String, Integer> longestAliasBySpan = new LinkedHashMap<>();
		for (MonthPending pending : matches) {
			String span = pending.match().start() + "|" + pending.match().end();
			longestAliasBySpan.merge(span, pending.aliasLength(), Math::max);
		}
		for (MonthPending pending : matches) {
			String span = pending.match().start() + "|" + pending.match().end();
			if (pending.aliasLength() == longestAliasBySpan.get(span)) destination.add(pending.match());
		}
	}

	private PendingMatch monthNameMatch(String text, int monthStart, int monthEnd,
			Set<Integer> months, boolean spanishAlias) {
		String beforeText = text.substring(0, monthStart);
		String afterText = text.substring(monthEnd);
		Matcher dayBefore = DAY_BEFORE_MONTH.matcher(beforeText);
		Matcher dayAfter = DAY_AFTER_MONTH.matcher(afterText);
		Set<LocalDate> dates = new LinkedHashSet<>();
		int matchStart = monthStart;
		int matchEnd = monthEnd;
		boolean explicitYear = false;

		if (dayBefore.find() && allowedMonthConnector(dayBefore.group("connector"), spanishAlias)) {
			int day = integer(dayBefore.group("day"));
			Matcher yearAfter = YEAR_AFTER.matcher(afterText);
			int year = Year.now(clock).getValue();
			if (yearAfter.find() && allowedMonthConnector(yearAfter.group("connector"), spanishAlias)) {
				year = normalizedYear(yearAfter.group("year"));
				matchEnd = monthEnd + yearAfter.end();
				explicitYear = true;
			}
			for (int month : months) addDate(dates, year, month, day);
			matchStart = dayBefore.start();
			if (!explicitYear) {
				Matcher yearBefore = YEAR_BEFORE.matcher(beforeText.substring(0, dayBefore.start()));
				if (yearBefore.find()) {
					dates.clear();
					for (int month : months) addDate(dates,
							normalizedYear(yearBefore.group("year")), month, day);
					matchStart = yearBefore.start();
					explicitYear = true;
				}
			}
		} else if (dayAfter.find() && allowedMonthConnector(dayAfter.group("connector"), spanishAlias)) {
			int day = integer(dayAfter.group("day"));
			int dayEnd = dayAfter.end();
			Matcher yearAfter = YEAR_AFTER.matcher(afterText.substring(dayEnd));
			int year = Year.now(clock).getValue();
			if (yearAfter.find() && allowedMonthConnector(yearAfter.group("connector"), spanishAlias)) {
				year = normalizedYear(yearAfter.group("year"));
				matchEnd = monthEnd + dayEnd + yearAfter.end();
				explicitYear = true;
			} else {
				matchEnd = monthEnd + dayEnd;
				Matcher yearBefore = YEAR_BEFORE.matcher(beforeText);
				if (yearBefore.find()) {
					year = normalizedYear(yearBefore.group("year"));
					matchStart = yearBefore.start();
					explicitYear = true;
				}
			}
			for (int month : months) addDate(dates, year, month, day);
		}
		return dates.isEmpty() ? null : new PendingMatch(matchStart, matchEnd, dates, explicitYear);
	}

	private boolean allowedMonthConnector(String connector, boolean spanishAlias) {
		return connector == null || !connector.strip().equalsIgnoreCase("de") || spanishAlias;
	}

	private void addNumericMatches(List<PendingMatch> destination, String text, Source source) {
		Set<String> occupied = new LinkedHashSet<>();
		List<Pattern> triplePatterns = source == Source.FREE_TEXT
				? List.of(SEPARATED_TRIPLE) : List.of(SEPARATED_TRIPLE, SPACED_TRIPLE);
		for (Pattern pattern : triplePatterns) {
			Matcher matcher = pattern.matcher(text);
			while (matcher.find()) {
				if (numberTouchesTimeColon(text, matcher, "a", "b", "c")) continue;
				occupied.add(matcher.start() + "|" + matcher.end());
				Set<LocalDate> dates = numericTriple(matcher.group("a"), matcher.group("b"), matcher.group("c"));
				if (!dates.isEmpty()) {
					destination.add(new PendingMatch(matcher.start(), matcher.end(), dates, true));
				}
			}
		}
		List<Pattern> pairPatterns = source == Source.FREE_TEXT
				? List.of(SEPARATED_PAIR) : List.of(SEPARATED_PAIR, SPACED_PAIR);
		for (Pattern pattern : pairPatterns) {
			Matcher matcher = pattern.matcher(text);
			while (matcher.find()) {
				if (numberTouchesTimeColon(text, matcher, "a", "b")) continue;
				if (insideOccupied(matcher.start(), matcher.end(), occupied)) continue;
				int a = integer(matcher.group("a"));
				int b = integer(matcher.group("b"));
				Set<LocalDate> dates = new LinkedHashSet<>();
				int year = Year.now(clock).getValue();
				addDate(dates, year, b, a);
				addDate(dates, year, a, b);
				if (!dates.isEmpty()) destination.add(new PendingMatch(
						matcher.start(), matcher.end(), dates, false));
			}
		}
	}

	private Set<LocalDate> numericTriple(String first, String second, String third) {
		Set<LocalDate> dates = new LinkedHashSet<>();
		int a = integer(first);
		int b = integer(second);
		int c = integer(third);
		if (first.length() == 4) {
			addDate(dates, a, b, c);
			return dates;
		}
		if (third.length() == 4) {
			addDate(dates, c, b, a);
			addDate(dates, c, a, b);
			return dates;
		}
		if (first.length() <= 2 && second.length() <= 2 && third.length() <= 2) {
			addDate(dates, normalizedYear(third), b, a);
			addDate(dates, normalizedYear(third), a, b);
			addDate(dates, normalizedYear(first), b, c);
		}
		return dates;
	}

	private void addCompactMatches(List<PendingMatch> destination, String text) {
		if (text.matches("\\d{8}")) {
			Set<LocalDate> dates = new LinkedHashSet<>();
			addDate(dates, integer(text.substring(0, 4)), integer(text.substring(4, 6)),
					integer(text.substring(6, 8)));
			destination.add(new PendingMatch(0, text.length(), dates, true));
		} else if (text.matches("\\d{6}")) {
			int a = integer(text.substring(0, 2));
			int b = integer(text.substring(2, 4));
			int c = integer(text.substring(4, 6));
			String trailingYear = text.substring(4, 6);
			Set<LocalDate> dates = new LinkedHashSet<>();
			addDate(dates, normalizedYear(trailingYear), b, a);
			addDate(dates, normalizedYear(trailingYear), a, b);
			addDate(dates, normalizedYear(text.substring(0, 2)), b, c);
			destination.add(new PendingMatch(0, text.length(), dates, true));
		}
	}

	private boolean numberTouchesTimeColon(String text, Matcher matcher, String... groups) {
		for (String group : groups) {
			int start = matcher.start(group);
			int end = matcher.end(group);
			if (start > 0 && text.charAt(start - 1) == ':'
					|| end < text.length() && text.charAt(end) == ':') return true;
		}
		return false;
	}

	private int normalizedYear(String digits) {
		int value = integer(digits);
		return digits.length() == 2 ? 2000 + value : value;
	}

	private void addDate(Set<LocalDate> dates, int year, int month, int day) {
		try {
			dates.add(LocalDate.of(year, month, day));
		} catch (DateTimeException ignored) {
			// Invalid printed dates do not create candidates.
		}
	}

	private int integer(String value) {
		return Integer.parseInt(value);
	}

	private boolean monthBoundary(String text, int start, int end) {
		return (start == 0 || !letterOrMark(text.codePointBefore(start)))
				&& (end == text.length() || !letterOrMark(text.codePointAt(end)));
	}

	private boolean letterOrMark(int codePoint) {
		int type = Character.getType(codePoint);
		return Character.isLetter(codePoint) || type == Character.NON_SPACING_MARK
				|| type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK;
	}

	private boolean insideOccupied(int start, int end, Set<String> occupied) {
		for (String value : occupied) {
			String[] bounds = value.split("\\|");
			int occupiedStart = Integer.parseInt(bounds[0]);
			int occupiedEnd = Integer.parseInt(bounds[1]);
			if (start >= occupiedStart && end <= occupiedEnd) return true;
		}
		return false;
	}

	private String rawSlice(String raw, String normalized, int start, int end) {
		if (raw == null) return null;
		if (raw.length() == normalized.length()) return raw.substring(start, end);
		return raw;
	}

	private static Map<String, Set<Integer>> buildMonthRegistry() {
		Map<String, Set<Integer>> aliases = new LinkedHashMap<>();
		for (Locale locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("es"))) {
			DateFormatSymbols symbols = DateFormatSymbols.getInstance(locale);
			registerMonths(aliases, symbols.getMonths());
			registerMonths(aliases, symbols.getShortMonths());
		}
		Map<String, Set<Integer>> immutable = new LinkedHashMap<>();
		aliases.forEach((alias, months) -> immutable.put(alias, Set.copyOf(months)));
		return Map.copyOf(immutable);
	}

	private static Set<String> buildMonthAliases(Locale locale) {
		Map<String, Set<Integer>> aliases = new LinkedHashMap<>();
		DateFormatSymbols symbols = DateFormatSymbols.getInstance(locale);
		registerMonths(aliases, symbols.getMonths());
		registerMonths(aliases, symbols.getShortMonths());
		return Set.copyOf(aliases.keySet());
	}

	private static void registerMonths(Map<String, Set<Integer>> aliases, String[] values) {
		for (int index = 0; index < Math.min(12, values.length); index++) {
			String normalized = normalizeMonthAlias(values[index]);
			if (normalized == null) continue;
			registerMonthAlias(aliases, normalized, index + 1);
			registerMonthAlias(aliases, foldAccents(normalized), index + 1);
		}
	}

	private static void registerMonthAlias(Map<String, Set<Integer>> aliases, String alias, int month) {
		if (alias == null || alias.isBlank()) return;
		aliases.computeIfAbsent(alias, ignored -> new LinkedHashSet<>()).add(month);
	}

	private static boolean usableMonthAlias(String alias) {
		return alias != null && alias.codePoints().filter(Character::isLetter).count() >= 3;
	}

	private static String normalizeMonthAlias(String value) {
		String normalized = normalize(value);
		if (normalized == null) return null;
		normalized = normalized.replaceFirst("\\.+$", "").trim();
		return normalized.isBlank() ? null : normalized;
	}

	private static String foldAccents(String value) {
		if (value == null) return null;
		String nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC);
		String nfd = Normalizer.normalize(nfkc, Normalizer.Form.NFD);
		StringBuilder folded = new StringBuilder(nfd.length());
		nfd.codePoints().forEach(codePoint -> {
			int type = Character.getType(codePoint);
			if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK
					&& type != Character.ENCLOSING_MARK) folded.appendCodePoint(codePoint);
		});
		return Normalizer.normalize(folded, Normalizer.Form.NFC)
				.toLowerCase(Locale.ROOT).replaceFirst("\\.+$", "");
	}
}
