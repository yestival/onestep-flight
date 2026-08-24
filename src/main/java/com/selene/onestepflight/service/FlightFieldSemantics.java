package com.selene.onestepflight.service;

import java.text.Normalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Field-aware comparison rules for values returned by documents and flight
 * providers. The methods deliberately return comparison keys only; callers
 * keep the original provider values as evidence and for display.
 */
final class FlightFieldSemantics {
	private static final Pattern BRACKETED_IATA = Pattern.compile("[\\(\\[（]\\s*([A-Za-z]{3})\\s*[\\)\\]）]");
	private static final Pattern UPPERCASE_IATA = Pattern.compile("(?:^|[^A-Za-z])([A-Z]{3})(?=$|[^A-Za-z])");
	private static final Pattern COUNTER_RANGE = Pattern.compile(
			"(?iu)(?:COUNTERS?|DESKS?|柜台|櫃檯)?\\s*#?\\s*(\\d{1,4})\\s*(?:-|–|—|TO|至|到)\\s*(\\d{1,4})");
	private static final Pattern SINGLE_COUNTER = Pattern.compile(
			"(?iu)(?:COUNTERS?|DESKS?|柜台|櫃檯)\\s*#?\\s*(\\d{1,4})");

	private FlightFieldSemantics() {
	}

	static boolean equivalent(String field, Object first, Object second) {
		if (isMissing(first) || isMissing(second)) return true;
		if (isAirportNameField(field)) return true;
		if (isCheckInField(field)) return checkInLocationsCompatible(first, second);
		if (isAirportField(field)) {
			String firstCode = airportCode(first);
			String secondCode = airportCode(second);
			// Airport identity conflicts require two actual IATA codes. Name-only
			// wording is retained in provenance but is not an actionable conflict.
			return firstCode == null || secondCode == null || firstCode.equals(secondCode);
		}
		return Objects.equals(normalize(field, first), normalize(field, second));
	}

	static boolean hasConflict(String field, List<?> values) {
		for (int first = 0; first < values.size(); first++) {
			for (int second = first + 1; second < values.size(); second++) {
				if (!equivalent(field, values.get(first), values.get(second))) return true;
			}
		}
		return false;
	}

	static String normalize(String field, Object value) {
		if (isMissing(value)) return null;
		return switch (field) {
			case "terminal" -> normalizeTerminal(value);
			case "gate" -> normalizeGate(value);
			case "flightNumber" -> normalizeFlightNumber(value);
			case "departureAirport", "arrivalAirport" -> airportCode(value);
			case "checkInDeskOrZone", "dedicatedBagDropDesk" -> normalizeCheckIn(value);
			case "status" -> normalizeStatusGroup(value);
			case "scheduledDeparture", "estimatedDeparture", "actualDeparture",
					"scheduledArrival", "estimatedArrival", "actualArrival", "boardingTime" -> normalizeTime(value);
			default -> normalizeLooseText(value);
		};
	}

	static int specificity(String field, Object value) {
		if (!isCheckInField(field) || isMissing(value)) return 0;
		CheckInLocation location = parseCheckIn(value);
		return (location.area() == null ? 0 : 1) + (location.hasCounters() ? 2 : 0);
	}

	static String normalizeTerminal(Object value) {
		String text = folded(value);
		if (text == null) return null;
		text = text.replaceFirst("(?iu)^\\s*(?:TERMINAL|TERM\\.?|航站楼|航站樓|航廈)\\s*[:#：号-]?\\s*", "");
		text = text.replaceFirst("(?iu)^\\s*T\\s*(?=\\d)", "");
		return stripNumericLeadingZeroes(compact(text));
	}

	static String normalizeGate(Object value) {
		String text = folded(value);
		if (text == null) return null;
		text = text.replaceFirst("(?iu)^\\s*(?:GATE|登机口|登機口)\\s*[:#：号-]?\\s*", "");
		String normalized = compact(text);
		if (normalized == null) return null;
		return normalized.replaceFirst("^([A-Z]+)0+(\\d+)$", "$1$2")
				.replaceFirst("^0+(?=\\d)", "");
	}

	static String normalizeFlightNumber(Object value) {
		String text = folded(value);
		if (text == null) return null;
		text = text.replaceFirst("(?iu)^\\s*(?:FLIGHT|航班)\\s*[:#：号-]?\\s*", "");
		return compact(text);
	}

	static String normalizeStatusGroup(Object value) {
		String status = normalizeLooseText(value);
		if (status == null) return null;
		if (status.contains("CANCEL") || status.contains("取消")) return "CANCELLED";
		if (status.contains("ARRIV") || status.contains("到达") || status.contains("抵达")) return "ARRIVED";
		if (status.contains("DEPART") || status.contains("起飞") || status.contains("已飞")) return "DEPARTED";
		if (status.contains("BOARD") || status.contains("登机")) return "BOARDING";
		if (status.contains("DELAY") || status.contains("LATE") || status.contains("延误")) return "DELAYED";
		if (status.contains("SCHEDULE") || status.contains("EXPECTED") || status.contains("ONTIME")
				|| status.contains("计划") || status.contains("预计") || status.contains("准点")) return "NORMAL";
		return status;
	}

	static Instant instant(Object value) {
		if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
		if (value instanceof Instant instant) return instant;
		if (!(value instanceof CharSequence)) return null;
		String text = value.toString().trim();
		try {
			return OffsetDateTime.parse(text).toInstant();
		} catch (DateTimeParseException ignored) {
			try {
				return Instant.parse(text);
			} catch (DateTimeParseException ignoredAgain) {
				return null;
			}
		}
	}

	private static String normalizeTime(Object value) {
		Instant instant = instant(value);
		return instant == null ? normalizeLooseText(value) : instant.toString();
	}

	private static boolean checkInLocationsCompatible(Object first, Object second) {
		CheckInLocation left = parseCheckIn(first);
		CheckInLocation right = parseCheckIn(second);
		if (left.area() != null && right.area() != null && !left.area().equals(right.area())) return false;
		if (!left.hasCounters() || !right.hasCounters()) return true;
		return left.counterStart() == right.counterStart() && left.counterEnd() == right.counterEnd();
	}

	private static String normalizeCheckIn(Object value) {
		CheckInLocation location = parseCheckIn(value);
		String area = location.area() == null ? "" : "AREA:" + location.area();
		if (!location.hasCounters()) return area.isEmpty() ? normalizeLooseText(value) : area;
		return area + "|COUNTERS:" + location.counterStart() + "-" + location.counterEnd();
	}

	private static CheckInLocation parseCheckIn(Object value) {
		String text = folded(value);
		if (text == null) return new CheckInLocation(null, null, null);
		text = text.replace('–', '-').replace('—', '-');
		Integer start = null;
		Integer end = null;
		Matcher range = COUNTER_RANGE.matcher(text);
		if (range.find()) {
			start = Integer.valueOf(range.group(1));
			end = Integer.valueOf(range.group(2));
			if (start > end) {
				int swap = start;
				start = end;
				end = swap;
			}
			text = range.replaceFirst(" ");
		} else {
			Matcher counter = SINGLE_COUNTER.matcher(text);
			if (counter.find()) {
				start = Integer.valueOf(counter.group(1));
				end = start;
				text = counter.replaceFirst(" ");
			}
		}
		text = text.replaceAll("(?iu)CHECK\\s*[- ]?\\s*IN", " ")
				.replaceAll("(?iu)\\b(?:AREA|ZONE|COUNTERS?|DESKS?)\\b", " ")
				.replaceAll("(?:值机区域|值機區域|值机区|值機區|值机柜台|值機櫃檯|区域|區域|区|區|柜台|櫃檯|号)", " ");
		String area = compact(text);
		return new CheckInLocation(area, start, end);
	}

	private static String airportCode(Object value) {
		String original = value.toString().trim();
		if (original.matches("(?i)[a-z]{3}")) return original.toUpperCase(Locale.ROOT);
		Matcher bracketed = BRACKETED_IATA.matcher(original);
		if (bracketed.find()) return bracketed.group(1).toUpperCase(Locale.ROOT);
		Matcher uppercase = UPPERCASE_IATA.matcher(original);
		return uppercase.find() ? uppercase.group(1) : null;
	}

	private static String normalizeLooseText(Object value) {
		return compact(folded(value));
	}

	private static String folded(Object value) {
		if (isMissing(value)) return null;
		return Normalizer.normalize(value.toString().trim(), Normalizer.Form.NFKC)
				.toUpperCase(Locale.ROOT);
	}

	private static String compact(String value) {
		if (value == null) return null;
		String compact = value.replaceAll("[\\p{Z}\\s\\p{P}\\p{S}]", "");
		return compact.isEmpty() ? null : compact;
	}

	private static String stripNumericLeadingZeroes(String value) {
		return value == null ? null : value.replaceFirst("^0+(?=\\d)", "");
	}

	private static boolean isAirportField(String field) {
		return "departureAirport".equals(field) || "arrivalAirport".equals(field);
	}

	private static boolean isAirportNameField(String field) {
		return "departureAirportName".equals(field) || "arrivalAirportName".equals(field);
	}

	private static boolean isCheckInField(String field) {
		return "checkInDeskOrZone".equals(field) || "dedicatedBagDropDesk".equals(field);
	}

	private static boolean isMissing(Object value) {
		if (value == null) return true;
		String text = value.toString().trim();
		return text.isEmpty() || "-".equals(text) || "NULL".equalsIgnoreCase(text)
				|| "N/A".equalsIgnoreCase(text);
	}

	private record CheckInLocation(String area, Integer counterStart, Integer counterEnd) {
		private boolean hasCounters() {
			return counterStart != null && counterEnd != null;
		}
	}
}
