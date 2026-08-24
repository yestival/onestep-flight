package com.selene.onestepflight.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.selene.onestepflight.dto.FieldCandidate;
import com.selene.onestepflight.dto.FieldProvenance;
import com.selene.onestepflight.dto.FlightFieldConflict;
import com.selene.onestepflight.dto.FlightResponse;
import com.selene.onestepflight.dto.ProviderFlightResult;

@Service
public class MultiSourceFlightService {

	private final List<OperationalFlightDataProvider> providers;

	public MultiSourceFlightService(List<OperationalFlightDataProvider> providers) {
		this.providers = List.copyOf(providers);
	}

	public MultiSourceFlightSnapshot findFlight(String flightNumber, LocalDate departureDate,
			String departureAirportHint, String arrivalAirportHint, boolean refresh) {
		OperationalFlightDataProvider aeroDataBox = providers.stream()
				.filter(provider -> "AERODATABOX".equals(provider.providerName()))
				.findFirst().orElse(null);
		ProviderFlightResult aeroDataBoxResult = aeroDataBox == null ? null
				: safeCall(aeroDataBox, flightNumber, departureDate,
						departureAirportHint, arrivalAirportHint, refresh);
		String effectiveDepartureHint = preferredHint(departureAirportHint,
				aeroDataBoxResult == null || !aeroDataBoxResult.successful()
						? null : aeroDataBoxResult.departureAirport());
		String effectiveArrivalHint = preferredHint(arrivalAirportHint,
				aeroDataBoxResult == null || !aeroDataBoxResult.successful()
						? null : aeroDataBoxResult.arrivalAirport());

		Map<String, CompletableFuture<ProviderFlightResult>> secondStageCalls = new LinkedHashMap<>();
		for (OperationalFlightDataProvider provider : providers) {
			if (provider == aeroDataBox) continue;
			secondStageCalls.put(provider.providerName(), CompletableFuture.supplyAsync(() -> safeCall(provider,
					flightNumber, departureDate, effectiveDepartureHint, effectiveArrivalHint, refresh)));
		}
		CompletableFuture.allOf(secondStageCalls.values().toArray(CompletableFuture[]::new)).join();
		Map<String, ProviderFlightResult> providerResults = new LinkedHashMap<>();
		for (OperationalFlightDataProvider provider : providers) {
			if (provider == aeroDataBox) {
				providerResults.put(provider.providerName(), aeroDataBoxResult);
			} else {
				providerResults.put(provider.providerName(), secondStageCalls.get(provider.providerName()).join());
			}
		}
		List<ProviderFlightResult> successful = providerResults.values().stream()
				.filter(ProviderFlightResult::successful).toList();
		if (successful.isEmpty()) throw new AllFlightProvidersUnavailableException(providerResults);

		Map<String, FieldProvenance> provenance = new LinkedHashMap<>();
		Choice<String> selectedFlightNumber = choose("flightNumber", successful,
				ProviderFlightResult::flightNumber, this::normalizeUpper, FieldPolicy.PLANNED, provenance);
		Choice<LocalDate> selectedDepartureDate = choose("departureDate", successful,
				ProviderFlightResult::departureDate, Objects::toString, FieldPolicy.PLANNED, provenance);
		Choice<String> departureAirport = choose("departureAirport", successful,
				ProviderFlightResult::departureAirport, this::normalizeUpper, FieldPolicy.PLANNED, provenance);
		Choice<String> departureAirportName = chooseAirportName("departureAirportName", successful,
				ProviderFlightResult::departureAirport, ProviderFlightResult::departureAirportName,
				departureAirport.value(), provenance);
		Choice<String> arrivalAirport = choose("arrivalAirport", successful,
				ProviderFlightResult::arrivalAirport, this::normalizeUpper, FieldPolicy.PLANNED, provenance);
		Choice<String> arrivalAirportName = chooseAirportName("arrivalAirportName", successful,
				ProviderFlightResult::arrivalAirport, ProviderFlightResult::arrivalAirportName,
				arrivalAirport.value(), provenance);
		Choice<OffsetDateTime> scheduledDeparture = choose("scheduledDeparture", successful,
				ProviderFlightResult::scheduledDeparture, this::normalizeTime, FieldPolicy.PLANNED, provenance);
		Choice<OffsetDateTime> estimatedDeparture = choose("estimatedDeparture", successful,
				ProviderFlightResult::estimatedDeparture, this::normalizeTime, FieldPolicy.OPERATIONAL, provenance);
		Choice<OffsetDateTime> actualDeparture = choose("actualDeparture", successful,
				ProviderFlightResult::actualDeparture, this::normalizeTime, FieldPolicy.OPERATIONAL, provenance);
		Choice<OffsetDateTime> scheduledArrival = choose("scheduledArrival", successful,
				ProviderFlightResult::scheduledArrival, this::normalizeTime, FieldPolicy.PLANNED, provenance);
		Choice<OffsetDateTime> estimatedArrival = choose("estimatedArrival", successful,
				ProviderFlightResult::estimatedArrival, this::normalizeTime, FieldPolicy.OPERATIONAL, provenance);
		Choice<OffsetDateTime> actualArrival = choose("actualArrival", successful,
				ProviderFlightResult::actualArrival, this::normalizeTime, FieldPolicy.OPERATIONAL, provenance);
		Choice<OffsetDateTime> boardingTime = choose("boardingTime", successful,
				ProviderFlightResult::boardingTime, this::normalizeTime, FieldPolicy.OPERATIONAL, provenance);
		Choice<String> terminal = choose("terminal", successful, ProviderFlightResult::terminal,
				this::normalizeTerminal, FieldPolicy.OPERATIONAL, provenance);
		Choice<String> gate = choose("gate", successful, ProviderFlightResult::gate,
				this::normalizeGate, FieldPolicy.OPERATIONAL, provenance);
		Choice<String> checkIn = choose("checkInDeskOrZone", successful,
				ProviderFlightResult::checkInDeskOrZone, this::normalizeLocationText, FieldPolicy.OPERATIONAL, provenance);
		Choice<String> bagDrop = choose("dedicatedBagDropDesk", successful,
				ProviderFlightResult::dedicatedBagDropDesk, this::normalizeLocationText, FieldPolicy.OPERATIONAL, provenance);
		Choice<String> arrivalBaggageBelt = choose("arrivalBaggageBelt", successful,
				ProviderFlightResult::arrivalBaggageBelt, this::normalizeLocationText,
				FieldPolicy.OPERATIONAL, provenance);
		Choice<String> status = choose("status", successful, ProviderFlightResult::status,
				this::normalizeStatusGroup, FieldPolicy.OPERATIONAL, provenance);
		Choice<Integer> departureDelay = choose("departureDelayMinutes", successful,
				ProviderFlightResult::departureDelayMinutes, Objects::toString, FieldPolicy.OPERATIONAL, provenance);
		Choice<Integer> arrivalDelay = choose("arrivalDelayMinutes", successful,
				ProviderFlightResult::arrivalDelayMinutes, Objects::toString, FieldPolicy.OPERATIONAL, provenance);
		Choice<String> sourceUrl = choose("sourceUrl", successful, ProviderFlightResult::sourceUrl,
				this::normalizeText, FieldPolicy.PLANNED, provenance);
		Choice<String> codeshare = choose("codeshare", successful, ProviderFlightResult::codeshare,
				this::normalizeUpper, FieldPolicy.PLANNED, provenance);

		if (departureAirport.value() == null || arrivalAirport.value() == null || scheduledDeparture.value() == null) {
			throw new AllFlightProvidersUnavailableException(providerResults);
		}
		Integer computedDepartureDelay = delayMinutes(scheduledDeparture.value(),
				firstNonNull(actualDeparture.value(), estimatedDeparture.value()));
		Integer computedArrivalDelay = delayMinutes(scheduledArrival.value(),
				firstNonNull(actualArrival.value(), estimatedArrival.value()));
		int departureDelayValue = departureDelay.value() == null
				? valueOrZero(computedDepartureDelay) : departureDelay.value();
		int arrivalDelayValue = arrivalDelay.value() == null ? valueOrZero(computedArrivalDelay) : arrivalDelay.value();
		Instant freshestUpdatedAt = successful.stream().map(ProviderFlightResult::sourceUpdatedAt)
				.filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
		Instant retrievedAt = successful.stream().map(ProviderFlightResult::retrievedAt)
				.filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(Instant.now());
		String sources = successful.stream().map(ProviderFlightResult::provider).sorted()
				.collect(Collectors.joining(",", "MERGED[", "]"));

		FlightResponse merged = new FlightResponse(firstNonNull(selectedFlightNumber.value(), flightNumber),
				firstNonNull(selectedDepartureDate.value(), departureDate),
				departureAirport.value(), departureAirportName.value(), arrivalAirport.value(), arrivalAirportName.value(),
				scheduledDeparture.value(), estimatedDeparture.value(), actualDeparture.value(),
				scheduledArrival.value(), estimatedArrival.value(), actualArrival.value(), boardingTime.value(),
				terminal.value(), gate.value(), checkIn.value(), status.value() == null ? "UNKNOWN" : status.value(),
				departureDelayValue, arrivalDelayValue, freshestUpdatedAt, retrievedAt, sources,
				sourceUrl.value(), codeshare.value(), true, checkIn.value() == null, false);
		List<FlightFieldConflict> conflicts = buildProviderConflicts(provenance, retrievedAt);
		return new MultiSourceFlightSnapshot(merged, bagDrop.value(), arrivalBaggageBelt.value(),
				Map.copyOf(providerResults),
				Map.copyOf(provenance), conflicts, retrievedAt, freshestUpdatedAt);
	}

	private ProviderFlightResult safeCall(OperationalFlightDataProvider provider, String flightNumber,
			LocalDate departureDate, String departureHint, String arrivalHint, boolean refresh) {
		try {
			return provider.findFlight(flightNumber, departureDate, departureHint, arrivalHint, refresh);
		} catch (RuntimeException exception) {
			return ProviderFlightResult.failure(provider.providerName(), "UPSTREAM_ERROR",
					provider.providerName() + "_UNEXPECTED_ERROR", Instant.now());
		}
	}

	private String preferredHint(String documentHint, String providerHint) {
		return documentHint != null && !documentHint.isBlank() ? documentHint : providerHint;
	}

	private <T> Choice<T> choose(String field, List<ProviderFlightResult> results,
			Function<ProviderFlightResult, T> getter, Function<T, String> normalizer,
			FieldPolicy policy, Map<String, FieldProvenance> provenance) {
		List<ValueCandidate<T>> candidates = results.stream()
				.filter(result -> policy != FieldPolicy.OPERATIONAL || !isScheduleOnly(result))
				.map(result -> new ValueCandidate<>(getter.apply(result), result))
				.filter(candidate -> hasValue(candidate.value()))
				.sorted(this.<T>valueCandidateComparator(policy).reversed())
				.toList();
		if (candidates.isEmpty()) return new Choice<>(null, null);
		ValueCandidate<T> selected = candidates.get(0);
		List<FieldCandidate> displayCandidates = candidates.stream()
				.map(candidate -> new FieldCandidate(stringValue(candidate.value()), candidate.result().provider(),
						candidate.result().sourceUpdatedAt(), candidate.result().retrievedAt(), candidate.result().live(),
						candidate.result().quality()))
				.toList();
		long distinct = candidates.stream().map(candidate -> normalizer.apply(candidate.value()))
				.filter(Objects::nonNull).distinct().count();
		FieldProvenance source = new FieldProvenance(stringValue(selected.value()), selected.result().provider(),
				selected.result().sourceUpdatedAt(), selected.result().retrievedAt(), selected.result().live(), distinct > 1,
				displayCandidates);
		provenance.put(field, source);
		return new Choice<>(selected.value(), source);
	}

	private Choice<String> chooseAirportName(String field, List<ProviderFlightResult> results,
			Function<ProviderFlightResult, String> airportGetter,
			Function<ProviderFlightResult, String> nameGetter, String selectedAirport,
			Map<String, FieldProvenance> provenance) {
		List<ProviderFlightResult> matchingAirport = results.stream()
				.filter(result -> Objects.equals(normalizeUpper(airportGetter.apply(result)),
						normalizeUpper(selectedAirport)))
				.toList();
		return choose(field, matchingAirport, nameGetter, ignored -> normalizeUpper(selectedAirport),
				FieldPolicy.PLANNED, provenance);
	}

	private <T> Comparator<ValueCandidate<T>> valueCandidateComparator(FieldPolicy policy) {
		return Comparator.<ValueCandidate<T>>comparingInt(candidate -> policy == FieldPolicy.OPERATIONAL
				? operationalQualityScore(candidate.result()) : plannedQualityScore(candidate.result()))
				.thenComparingInt(candidate -> candidate.result().sourceUpdatedAt() == null ? 0 : 1)
				.thenComparing(candidate -> candidate.result().sourceUpdatedAt(),
						Comparator.nullsFirst(Comparator.naturalOrder()))
				.thenComparing(candidate -> candidate.result().retrievedAt(),
						Comparator.nullsFirst(Comparator.naturalOrder()));
	}

	private int operationalQualityScore(ProviderFlightResult result) {
		String normalized = result.quality() == null ? "" : result.quality().toUpperCase(Locale.ROOT);
		if (normalized.contains("LIVE")) return 5;
		if (normalized.contains("ESTIMATED") || normalized.contains("REAL")) return 4;
		if (normalized.contains("STATUS_METADATA")) return 3;
		if (normalized.contains("BASIC")) return 2;
		if (normalized.contains("SCHEDULE")) return 0;
		return result.live() ? 2 : 1;
	}

	private int plannedQualityScore(ProviderFlightResult result) {
		String normalized = result.quality() == null ? "" : result.quality().toUpperCase(Locale.ROOT);
		if (normalized.contains("LIVE") || normalized.contains("REAL") || normalized.contains("ESTIMATED")) return 5;
		if (normalized.contains("STATUS_METADATA") || normalized.contains("BASIC")) return 4;
		if (normalized.contains("SCHEDULE")) return 3;
		return result.live() ? 2 : 1;
	}

	private List<FlightFieldConflict> buildProviderConflicts(Map<String, FieldProvenance> provenance,
			Instant detectedAt) {
		List<FlightFieldConflict> conflicts = new ArrayList<>();
		addIdentityConflict(conflicts, "departureAirport", provenance.get("departureAirport"), detectedAt);
		addIdentityConflict(conflicts, "arrivalAirport", provenance.get("arrivalAirport"), detectedAt);
		addLiveValueConflict(conflicts, "terminal", provenance.get("terminal"), detectedAt);
		addLiveValueConflict(conflicts, "gate", provenance.get("gate"), detectedAt);
		addCancellationConflict(conflicts, provenance.get("status"), detectedAt);
		addLiveTimeConflict(conflicts, "estimatedDeparture", provenance.get("estimatedDeparture"), detectedAt);
		addLiveTimeConflict(conflicts, "actualDeparture", provenance.get("actualDeparture"), detectedAt);
		return List.copyOf(conflicts);
	}

	private void addIdentityConflict(List<FlightFieldConflict> conflicts, String field,
			FieldProvenance source, Instant detectedAt) {
		if (source == null || !source.conflict()) return;
		addCriticalProviderConflict(conflicts, field, source,
				"实时来源报告了不同的机场代码，请先向工作人员核对。",
				"Live sources report different airport codes. Stop and confirm with staff.", detectedAt);
	}

	private void addLiveValueConflict(List<FlightFieldConflict> conflicts, String field,
			FieldProvenance source, Instant detectedAt) {
		if (source == null) return;
		List<FieldCandidate> liveCandidates = source.candidateValues().stream()
				.filter(this::isLiveOperationalCandidate).toList();
		long distinct = liveCandidates.stream().map(candidate -> "terminal".equals(field)
				? normalizeTerminal(candidate.value()) : normalizeGate(candidate.value()))
				.filter(Objects::nonNull).distinct().count();
		if (liveCandidates.size() < 2 || distinct < 2) return;
		addCriticalProviderConflict(conflicts, field, source,
				"两个实时运营来源报告了不同的" + ("terminal".equals(field) ? "航站楼" : "登机口") + "，请先向工作人员核对。",
				"Two live operational sources report different " + field + " values. Stop and confirm with staff.",
				detectedAt);
	}

	private void addCancellationConflict(List<FlightFieldConflict> conflicts,
			FieldProvenance source, Instant detectedAt) {
		if (source == null) return;
		List<String> groups = source.candidateValues().stream().map(FieldCandidate::value)
				.map(this::normalizeStatusGroup).filter(Objects::nonNull).distinct().toList();
		if (!groups.contains("CANCELLED") || groups.size() < 2) return;
		addCriticalProviderConflict(conflicts, "status", source,
				"一个来源报告航班已取消，另一个来源未报告取消，请先向工作人员核对。",
				"One source reports cancellation while another does not. Stop and confirm with staff.", detectedAt);
	}

	private void addLiveTimeConflict(List<FlightFieldConflict> conflicts, String field,
			FieldProvenance source, Instant detectedAt) {
		if (source == null) return;
		List<OffsetDateTime> times = source.candidateValues().stream()
				.filter(this::isLiveOperationalCandidate)
				.map(FieldCandidate::value).map(this::parseTime).filter(Objects::nonNull).toList();
		if (times.size() < 2) return;
		Instant earliest = times.stream().map(OffsetDateTime::toInstant).min(Comparator.naturalOrder()).orElseThrow();
		Instant latest = times.stream().map(OffsetDateTime::toInstant).max(Comparator.naturalOrder()).orElseThrow();
		if (Math.abs(ChronoUnit.MINUTES.between(earliest, latest)) < 15) return;
		addCriticalProviderConflict(conflicts, field, source,
				"两个实时运营来源报告的起飞时间相差至少 15 分钟，请先向工作人员核对。",
				"Two live operational sources report departure times at least 15 minutes apart. Stop and confirm with staff.",
				detectedAt);
	}

	private void addCriticalProviderConflict(List<FlightFieldConflict> conflicts, String field,
			FieldProvenance source, String messageZh, String messageEn, Instant detectedAt) {
		String alternatives = source.candidateValues().stream()
				.map(candidate -> candidate.provider() + ": " + candidate.value())
				.collect(Collectors.joining(" | "));
		conflicts.add(new FlightFieldConflict(field, alternatives, source.value(), true,
				messageZh, messageEn, detectedAt));
	}

	private boolean isLiveOperationalCandidate(FieldCandidate candidate) {
		if (!candidate.live() || candidate.quality() == null) return false;
		String quality = candidate.quality().toUpperCase(Locale.ROOT);
		return quality.contains("LIVE") || quality.contains("REAL") || quality.contains("ESTIMATED");
	}

	private boolean isScheduleOnly(ProviderFlightResult result) {
		return result.quality() != null && result.quality().toUpperCase(Locale.ROOT).contains("SCHEDULE");
	}

	private boolean hasValue(Object value) {
		return value != null && (!(value instanceof String text) || !text.isBlank());
	}

	private String stringValue(Object value) {
		return value == null ? null : value.toString();
	}

	private String normalizeUpper(Object value) {
		return value == null ? null : value.toString().replaceAll("\\s", "").toUpperCase(Locale.ROOT);
	}

	private String normalizeTerminal(Object value) {
		return value == null ? null : value.toString().replaceAll("(?i)terminal", "")
				.replaceAll("[\\s\\p{Punct}]", "").toUpperCase(Locale.ROOT);
	}

	private String normalizeGate(Object value) {
		String normalized = normalizeLocationText(value);
		return normalized == null ? null : normalized.replaceFirst("^([A-Z]+)0+([0-9]+)$", "$1$2");
	}

	private String normalizeLocationText(Object value) {
		return value == null ? null : value.toString().replaceAll("[\\s\\p{Punct}]", "")
				.toUpperCase(Locale.ROOT);
	}

	private String normalizeText(Object value) {
		return value == null ? null : value.toString().trim().toUpperCase(Locale.ROOT);
	}

	private String normalizeStatusGroup(Object value) {
		if (value == null) return null;
		String status = value.toString().replaceAll("[\\s_-]", "").toUpperCase(Locale.ROOT);
		if (status.contains("CANCEL")) return "CANCELLED";
		if (status.contains("ARRIV")) return "ARRIVED";
		if (status.contains("DEPART")) return "DEPARTED";
		if (status.contains("BOARD")) return "BOARDING";
		if (status.contains("DELAY") || status.contains("LATE")) return "DELAYED";
		if (status.contains("SCHEDULE") || status.contains("EXPECTED") || status.contains("ONTIME")) return "NORMAL";
		return status;
	}

	private String normalizeTime(OffsetDateTime value) {
		return value == null ? null : value.toInstant().toString();
	}

	private OffsetDateTime parseTime(String value) {
		try {
			return value == null ? null : OffsetDateTime.parse(value);
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private Integer delayMinutes(OffsetDateTime scheduled, OffsetDateTime effective) {
		return scheduled == null || effective == null ? null
				: Math.max(0, Math.toIntExact(ChronoUnit.MINUTES.between(scheduled.toInstant(), effective.toInstant())));
	}

	private int valueOrZero(Integer value) {
		return value == null ? 0 : value;
	}

	@SafeVarargs
	private final <T> T firstNonNull(T... values) {
		for (T value : values) if (value != null) return value;
		return null;
	}

	private record ValueCandidate<T>(T value, ProviderFlightResult result) {
	}

	private record Choice<T>(T value, FieldProvenance provenance) {
	}

	private enum FieldPolicy {
		PLANNED,
		OPERATIONAL
	}
}
