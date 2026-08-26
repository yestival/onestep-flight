package com.selene.onestepflight.service.airline;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable, runtime-local airline registry. The default instance reads a
 * pinned classpath snapshot and performs no network I/O.
 */
public final class AirlineRegistry {
	private static final String SNAPSHOT_RESOURCE = "/airlines/openflights-airlines.dat";
	private static final String METADATA_RESOURCE = "/airlines/openflights-airlines.properties";
	private static final Pattern IATA = Pattern.compile("[A-Z0-9]{2}");
	private static final Pattern ICAO = Pattern.compile("[A-Z]{3}");
	private static final Pattern IATA_FLIGHT = Pattern.compile("^([A-Z0-9]{2})\\d{1,4}[A-Z]?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern ICAO_FLIGHT = Pattern.compile("^([A-Z]{3})\\d{1,4}[A-Z]?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern ORDINARY_FLIGHT_SEPARATORS = Pattern.compile("[\\p{Z}\\s\\-‐‑‒–—_./:]+");
	private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
	private static final Pattern IDENTITY_SEPARATORS = Pattern.compile("[\\p{Z}\\s\\p{P}\\p{S}]+");

	private final AirlineRegistryMetadata metadata;
	private final List<AirlineRecord> records;
	private final Map<String, List<AirlineRecord>> byIata;
	private final Map<String, List<AirlineRecord>> byIcao;
	private final Map<String, List<AirlineRecord>> byName;
	private final Map<String, List<AirlineRecord>> byAlias;
	private final Map<String, List<AirlineRecord>> byCallsign;
	private final Map<AirlineRecord, Integer> sourceOrder;

	private AirlineRegistry(AirlineRegistryMetadata metadata, List<AirlineRecord> records) {
		this.metadata = Objects.requireNonNull(metadata, "metadata");
		this.records = List.copyOf(records);
		this.sourceOrder = new IdentityHashMap<>();
		for (int index = 0; index < this.records.size(); index++) {
			this.sourceOrder.put(this.records.get(index), index);
		}
		this.byIata = index(this.records, AirlineRecord::iata, AirlineRegistry::codeKey, IATA);
		this.byIcao = index(this.records, AirlineRecord::icao, AirlineRegistry::codeKey, ICAO);
		this.byName = index(this.records, AirlineRecord::name, AirlineRegistry::identityKey, null);
		this.byAlias = index(this.records, AirlineRecord::alias, AirlineRegistry::identityKey, null);
		this.byCallsign = index(this.records, AirlineRecord::callsign, AirlineRegistry::identityKey, null);
	}

	public static AirlineRegistry loadDefault() {
		try (InputStream metadataStream = requiredResource(METADATA_RESOURCE);
				InputStream snapshotStream = requiredResource(SNAPSHOT_RESOURCE)) {
			return fromSnapshot(snapshotStream.readAllBytes(), readMetadata(metadataStream));
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to load bundled airline registry", exception);
		}
	}

	static AirlineRegistry fromSnapshot(byte[] snapshot, AirlineRegistryMetadata metadata) {
		Objects.requireNonNull(snapshot, "snapshot");
		Objects.requireNonNull(metadata, "metadata");
		String actualHash = sha256(snapshot);
		if (!actualHash.equalsIgnoreCase(metadata.originalSha256())) {
			throw new IllegalStateException("Airline registry checksum mismatch");
		}
		List<AirlineRecord> records = parse(snapshot);
		if (records.size() != metadata.recordCount()) {
			throw new IllegalStateException("Airline registry record count mismatch: expected "
					+ metadata.recordCount() + " but found " + records.size());
		}
		return new AirlineRegistry(metadata, records);
	}

	public AirlineRegistryMetadata metadata() {
		return metadata;
	}

	public List<AirlineRecord> records() {
		return records;
	}

	public AirlineResolution lookupIata(String query) {
		String key = codeKey(query);
		if (key == null || !IATA.matcher(key).matches()) return noMatch(key);
		return resolve(key, AirlineMatchType.IATA, byIata.getOrDefault(key, List.of()));
	}

	public AirlineResolution lookupIcao(String query) {
		String key = codeKey(query);
		if (key == null || !ICAO.matcher(key).matches()) return noMatch(key);
		return resolve(key, AirlineMatchType.ICAO, byIcao.getOrDefault(key, List.of()));
	}

	/** Exact normalized lookup across IATA, ICAO, name, alias, and callsign. */
	public AirlineResolution searchIdentity(String query) {
		String identity = identityKey(query);
		if (identity == null) return noMatch(null);
		String code = codeKey(query);
		Set<AirlineRecord> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<AirlineRecord> candidates = new ArrayList<>();
		EnumSet<AirlineMatchType> matches = EnumSet.noneOf(AirlineMatchType.class);

		if (code != null && IATA.matcher(code).matches()) {
			addCandidates(candidates, seen, byIata.get(code));
			if (byIata.containsKey(code)) matches.add(AirlineMatchType.IATA);
		}
		if (code != null && ICAO.matcher(code).matches()) {
			addCandidates(candidates, seen, byIcao.get(code));
			if (byIcao.containsKey(code)) matches.add(AirlineMatchType.ICAO);
		}
		addIdentityCandidates(identity, AirlineMatchType.NAME, byName, candidates, seen, matches);
		addIdentityCandidates(identity, AirlineMatchType.ALIAS, byAlias, candidates, seen, matches);
		addIdentityCandidates(identity, AirlineMatchType.CALLSIGN, byCallsign, candidates, seen, matches);
		candidates.sort(Comparator.comparingInt(sourceOrder::get));
		AirlineMatchType matchType = matches.isEmpty() ? AirlineMatchType.NONE
				: matches.size() == 1 ? matches.iterator().next() : AirlineMatchType.MULTIPLE_IDENTIFIERS;
		return resolve(identity, matchType, candidates);
	}

	/**
	 * Resolves either a bare two-character IATA / three-letter ICAO prefix or a
	 * complete designator such as "G9 123" or "AAL123". It never guesses by
	 * route and never performs a remote lookup.
	 */
	public AirlineResolution resolveFlightPrefix(String query) {
		String normalized = normalizeFlightDesignator(query);
		if (normalized == null) return noMatch(null);
		if (IATA.matcher(normalized).matches()) return lookupIata(normalized);
		if (ICAO.matcher(normalized).matches()) return lookupIcao(normalized);
		Matcher iataFlight = IATA_FLIGHT.matcher(normalized);
		if (iataFlight.matches()) return lookupIata(iataFlight.group(1));
		Matcher icaoFlight = ICAO_FLIGHT.matcher(normalized);
		if (icaoFlight.matches()) return lookupIcao(icaoFlight.group(1));
		return noMatch(normalized);
	}

	private static AirlineResolution resolve(String query, AirlineMatchType matchType,
			List<AirlineRecord> candidates) {
		List<AirlineRecord> safeCandidates = candidates == null ? List.of() : List.copyOf(candidates);
		long activeCount = safeCandidates.stream()
				.filter(candidate -> candidate.status() == AirlineStatus.ACTIVE)
				.count();
		boolean ambiguous = activeCount > 1 || (activeCount == 0 && safeCandidates.size() > 1);
		AirlineResolutionConfidence confidence;
		if (safeCandidates.isEmpty()) confidence = AirlineResolutionConfidence.NONE;
		else if (activeCount == 1) confidence = AirlineResolutionConfidence.HIGH;
		else if (safeCandidates.size() == 1) confidence = AirlineResolutionConfidence.MEDIUM;
		else confidence = AirlineResolutionConfidence.LOW;
		return new AirlineResolution(query, matchType, safeCandidates, confidence, ambiguous);
	}

	private static AirlineResolution noMatch(String normalizedQuery) {
		return new AirlineResolution(normalizedQuery, AirlineMatchType.NONE, List.of(),
				AirlineResolutionConfidence.NONE, false);
	}

	private static void addIdentityCandidates(String key, AirlineMatchType type,
			Map<String, List<AirlineRecord>> index, List<AirlineRecord> candidates,
			Set<AirlineRecord> seen, EnumSet<AirlineMatchType> matches) {
		List<AirlineRecord> found = index.get(key);
		if (found == null || found.isEmpty()) return;
		matches.add(type);
		addCandidates(candidates, seen, found);
	}

	private static void addCandidates(List<AirlineRecord> destination, Set<AirlineRecord> seen,
			List<AirlineRecord> found) {
		if (found == null) return;
		for (AirlineRecord record : found) {
			if (seen.add(record)) destination.add(record);
		}
	}

	private static List<AirlineRecord> parse(byte[] snapshot) {
		List<AirlineRecord> records = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new ByteArrayInputStream(snapshot), StandardCharsets.UTF_8))) {
			String line;
			int lineNumber = 0;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (line.isBlank()) continue;
				List<String> fields = parseCsvLine(line, lineNumber);
				if (fields.size() != 8) {
					throw new IllegalStateException("Invalid airline snapshot row at line " + lineNumber
							+ ": expected 8 columns but found " + fields.size());
				}
				records.add(new AirlineRecord(
						parseSourceId(fields.get(0), lineNumber),
						nullable(fields.get(1)),
						nullable(fields.get(2)),
						normalizeStoredCode(fields.get(3)),
						normalizeStoredCode(fields.get(4)),
						nullable(fields.get(5)),
						nullable(fields.get(6)),
						parseStatus(fields.get(7)),
						nullable(fields.get(7)),
						null,
						null));
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to parse airline snapshot", exception);
		}
		return records;
	}

	private static List<String> parseCsvLine(String line, int lineNumber) {
		List<String> fields = new ArrayList<>();
		StringBuilder field = new StringBuilder();
		boolean quoted = false;
		for (int index = 0; index < line.length(); index++) {
			char character = line.charAt(index);
			if (character == '"') {
				if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
					field.append('"');
					index++;
				} else {
					quoted = !quoted;
				}
			} else if (character == ',' && !quoted) {
				fields.add(field.toString());
				field.setLength(0);
			} else {
				field.append(character);
			}
		}
		if (quoted) throw new IllegalStateException("Unclosed CSV quote at line " + lineNumber);
		fields.add(field.toString());
		return fields;
	}

	private static long parseSourceId(String value, int lineNumber) {
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException exception) {
			throw new IllegalStateException("Invalid airline source id at line " + lineNumber, exception);
		}
	}

	private static AirlineStatus parseStatus(String value) {
		String status = nullable(value);
		if (status == null) return AirlineStatus.UNKNOWN;
		return switch (status.toUpperCase(Locale.ROOT)) {
			case "Y", "ACTIVE" -> AirlineStatus.ACTIVE;
			case "N", "INACTIVE" -> AirlineStatus.INACTIVE;
			case "H", "HISTORICAL" -> AirlineStatus.HISTORICAL;
			default -> AirlineStatus.UNKNOWN;
		};
	}

	private static String nullable(String value) {
		if (value == null) return null;
		String trimmed = value.trim();
		return trimmed.isEmpty() || "\\N".equalsIgnoreCase(trimmed) ? null : trimmed;
	}

	private static String normalizeStoredCode(String value) {
		String code = codeKey(value);
		if (code == null || "-".equals(code) || "N/A".equals(code)) return null;
		return code;
	}

	private static String normalizeFlightDesignator(String value) {
		if (value == null) return null;
		String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
				.toUpperCase(Locale.ROOT);
		normalized = ORDINARY_FLIGHT_SEPARATORS.matcher(normalized).replaceAll("");
		return normalized.isEmpty() ? null : normalized;
	}

	private static String codeKey(String value) {
		if (value == null) return null;
		String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
				.toUpperCase(Locale.ROOT);
		return normalized.isEmpty() ? null : normalized;
	}

	private static String identityKey(String value) {
		if (value == null) return null;
		String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKD);
		normalized = COMBINING_MARKS.matcher(normalized).replaceAll("")
				.toUpperCase(Locale.ROOT);
		normalized = IDENTITY_SEPARATORS.matcher(normalized).replaceAll(" ").trim();
		return normalized.isEmpty() ? null : normalized;
	}

	private static Map<String, List<AirlineRecord>> index(List<AirlineRecord> records,
			java.util.function.Function<AirlineRecord, String> accessor,
			java.util.function.Function<String, String> keyNormalizer, Pattern acceptedKey) {
		Map<String, List<AirlineRecord>> mutable = new LinkedHashMap<>();
		for (AirlineRecord record : records) {
			String key = keyNormalizer.apply(accessor.apply(record));
			if (key == null || (acceptedKey != null && !acceptedKey.matcher(key).matches())) continue;
			mutable.computeIfAbsent(key, ignored -> new ArrayList<>()).add(record);
		}
		Map<String, List<AirlineRecord>> immutable = new HashMap<>();
		mutable.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
		return Map.copyOf(immutable);
	}

	private static InputStream requiredResource(String path) {
		InputStream stream = AirlineRegistry.class.getResourceAsStream(path);
		if (stream == null) throw new IllegalStateException("Missing airline registry resource " + path);
		return stream;
	}

	private static AirlineRegistryMetadata readMetadata(InputStream stream) throws IOException {
		Properties properties = new Properties();
		properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
		return new AirlineRegistryMetadata(
				required(properties, "format.version"),
				required(properties, "source.name"),
				required(properties, "source.page"),
				required(properties, "source.repository"),
				required(properties, "source.revision"),
				instant(properties, "source.revisionAt"),
				required(properties, "source.file"),
				required(properties, "source.url"),
				required(properties, "source.databaseLicense"),
				required(properties, "source.databaseLicenseUrl"),
				required(properties, "source.contentsLicense"),
				required(properties, "source.contentsLicenseUrl"),
				required(properties, "source.attribution"),
				required(properties, "snapshot.originalSha256"),
				integer(properties, "snapshot.recordCount"),
				instant(properties, "snapshot.generatedAt"),
				required(properties, "snapshot.generatedBy"),
				required(properties, "source.schema"),
				required(properties, "source.statusSemantics"),
				required(properties, "snapshot.coverage"),
				required(properties, "snapshot.knownLimitations"),
				required(properties, "source.additionalRightsCaveat"));
	}

	private static String required(Properties properties, String key) {
		String value = properties.getProperty(key);
		if (value == null || value.isBlank()) throw new IllegalStateException("Missing airline metadata " + key);
		return value.trim();
	}

	private static int integer(Properties properties, String key) {
		try {
			return Integer.parseInt(required(properties, key));
		} catch (NumberFormatException exception) {
			throw new IllegalStateException("Invalid airline metadata " + key, exception);
		}
	}

	private static Instant instant(Properties properties, String key) {
		try {
			return Instant.parse(required(properties, key));
		} catch (DateTimeParseException exception) {
			throw new IllegalStateException("Invalid airline metadata " + key, exception);
		}
	}

	static String sha256(byte[] bytes) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			StringBuilder value = new StringBuilder(digest.length * 2);
			for (byte item : digest) value.append(String.format(Locale.ROOT, "%02x", item));
			return value.toString();
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}
}
