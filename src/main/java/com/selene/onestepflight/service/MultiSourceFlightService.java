package com.selene.onestepflight.service;

import java.time.Duration;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import com.selene.onestepflight.dto.FieldCandidate;
import com.selene.onestepflight.dto.FieldProvenance;
import com.selene.onestepflight.dto.FlightFieldConflict;
import com.selene.onestepflight.dto.FlightResponse;
import com.selene.onestepflight.dto.ProviderFlightResult;

@Service
public class MultiSourceFlightService {
	private static final Duration CACHE_TTL = Duration.ofMinutes(5);
	private static final Duration AGGREGATE_TIMEOUT = Duration.ofSeconds(15);
	private static final Duration AERODATABOX_TIMEOUT = Duration.ofSeconds(8);
	private static final Duration SERPAPI_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration ROUTE_HINT_STAGGER = Duration.ofMillis(200);
	private static final Duration SUFFICIENT_RESULT_GRACE = Duration.ofMillis(300);
	private static final Pattern EXPLICIT_FLIGHT_NUMBER = Pattern.compile(
			"(?i)(?<![A-Z0-9])([A-Z0-9]{2,3})[\\s\\-‐‑‒–—−]*([0-9]{1,4}[A-Z]?)(?![A-Z0-9])");

	private final List<OperationalFlightDataProvider> providers;
	private final Duration cacheTtl;
	private final Duration aggregateTimeout;
	private final Duration aeroDataBoxTimeout;
	private final Duration serpApiTimeout;
	private final Executor executor;
	private final Map<QueryKey, CachedSnapshot> cache = new ConcurrentHashMap<>();
	private final Map<QueryKey, CompletableFuture<MultiSourceFlightSnapshot>> inFlight = new ConcurrentHashMap<>();

	@Autowired
	public MultiSourceFlightService(List<OperationalFlightDataProvider> providers) {
		this(providers, CACHE_TTL, AGGREGATE_TIMEOUT,
				AERODATABOX_TIMEOUT, SERPAPI_TIMEOUT, ForkJoinPool.commonPool());
	}

	MultiSourceFlightService(List<OperationalFlightDataProvider> providers, Duration cacheTtl,
			Duration aggregateTimeout, Duration aeroDataBoxTimeout,
			Duration serpApiTimeout, Executor executor) {
		this.providers = providers.stream().sorted(Comparator.comparingInt(this::providerPriority)).toList();
		this.cacheTtl = cacheTtl;
		this.aggregateTimeout = aggregateTimeout;
		this.aeroDataBoxTimeout = aeroDataBoxTimeout;
		this.serpApiTimeout = serpApiTimeout;
		this.executor = executor;
	}

	public MultiSourceFlightSnapshot findFlight(String flightNumber, LocalDate departureDate,
			String departureAirportHint, String arrivalAirportHint, boolean refresh) {
		return findFlight(flightNumber, departureDate, departureAirportHint, arrivalAirportHint, null, refresh);
	}

	public MultiSourceFlightSnapshot findFlight(String flightNumber, LocalDate departureDate,
			String departureAirportHint, String arrivalAirportHint,
			String scheduledDepartureHint, boolean refresh) {
		String normalizedFlightNumber = normalizeUpper(flightNumber);
		String normalizedDepartureHint = normalizeAirportHint(departureAirportHint);
		String normalizedArrivalHint = normalizeAirportHint(arrivalAirportHint);
		String normalizedScheduledHint = normalizeTimeHint(scheduledDepartureHint);
		QueryKey key = new QueryKey(normalizedFlightNumber, departureDate,
				normalizedDepartureHint, normalizedArrivalHint, normalizedScheduledHint);
		Instant now = Instant.now();
		CachedSnapshot cached = cache.get(key);
		if (!refresh && isFresh(cached, now)) return cached.snapshot();

		CompletableFuture<MultiSourceFlightSnapshot> ownCall = new CompletableFuture<>();
		CompletableFuture<MultiSourceFlightSnapshot> existing = inFlight.putIfAbsent(key, ownCall);
		if (existing != null) return awaitCombined(existing);
		try {
			MultiSourceFlightSnapshot snapshot = loadFlight(normalizedFlightNumber, departureDate,
					normalizedDepartureHint, normalizedArrivalHint, normalizedScheduledHint, refresh);
			cache.put(key, new CachedSnapshot(snapshot, Instant.now()));
			ownCall.complete(snapshot);
			return snapshot;
		} catch (RuntimeException exception) {
			ownCall.completeExceptionally(exception);
			throw exception;
		} finally {
			inFlight.remove(key, ownCall);
		}
	}

	private MultiSourceFlightSnapshot loadFlight(String flightNumber, LocalDate departureDate,
			String departureAirportHint, String arrivalAirportHint,
			String scheduledDepartureHint, boolean refresh) {
		Instant deadline = Instant.now().plus(aggregateTimeout);
		OperationalFlightDataProvider aeroDataBox = providers.stream()
				.filter(provider -> "AERODATABOX".equals(provider.providerName()))
				.findFirst().orElse(null);
		Map<String, CompletableFuture<ProviderFlightResult>> calls = new LinkedHashMap<>();
		CompletableFuture<ProviderFlightResult> aeroDataBoxCall = null;
		if (aeroDataBox != null) {
			aeroDataBoxCall = startProviderCall(aeroDataBox, flightNumber, departureDate,
					departureAirportHint, arrivalAirportHint, scheduledDepartureHint,
					refresh, aeroDataBoxTimeout, deadline);
			calls.put(aeroDataBox.providerName(), aeroDataBoxCall);
		}
		ProviderFlightResult aeroDataBoxResult = awaitRouteHint(aeroDataBox, aeroDataBoxCall, deadline);
		RouteHints effectiveRoute = effectiveRouteHints(departureAirportHint, arrivalAirportHint,
				aeroDataBoxResult);
		String effectiveDepartureHint = effectiveRoute.departureAirport();
		String effectiveArrivalHint = effectiveRoute.arrivalAirport();

		for (OperationalFlightDataProvider provider : providers) {
			if (provider == aeroDataBox) continue;
			calls.put(provider.providerName(), startProviderCall(provider, flightNumber, departureDate,
					effectiveDepartureHint, effectiveArrivalHint, scheduledDepartureHint,
					refresh, providerBudget(provider), deadline));
		}
		Map<String, ProviderFlightResult> providerResults = new LinkedHashMap<>();
		if (aeroDataBoxResult != null) providerResults.put(aeroDataBox.providerName(), aeroDataBoxResult);
		collectProviderResults(calls, providerResults, deadline);
		Map<String, ProviderFlightResult> orderedResults = new LinkedHashMap<>();
		for (OperationalFlightDataProvider provider : providers) {
			ProviderFlightResult result = providerResults.get(provider.providerName());
			if (result != null) orderedResults.put(provider.providerName(), result);
		}
		providerResults = orderedResults;
		List<ProviderFlightResult> successful = providerResults.values().stream()
				.filter(ProviderFlightResult::successful).toList();
		if (successful.isEmpty()) throw new AllFlightProvidersUnavailableException(providerResults);
		if (departureDate != null && successful.stream()
				.anyMatch(result -> departureDate.equals(result.departureDate()))) {
			successful = successful.stream()
					.filter(result -> departureDate.equals(result.departureDate())).toList();
		}

		Map<String, FieldProvenance> provenance = new LinkedHashMap<>();
		Choice<String> selectedFlightNumber = choose("flightNumber", successful,
				ProviderFlightResult::flightNumber, FieldPolicy.PLANNED, provenance);
		Choice<LocalDate> selectedDepartureDate = choose("departureDate", successful,
				ProviderFlightResult::departureDate, FieldPolicy.PLANNED, provenance);
		Choice<String> departureAirport = choose("departureAirport", successful,
				ProviderFlightResult::departureAirport, FieldPolicy.PLANNED, provenance);
		Choice<String> departureAirportName = chooseAirportName("departureAirportName", successful,
				ProviderFlightResult::departureAirport, ProviderFlightResult::departureAirportName,
				departureAirport.value(), provenance);
		Choice<String> arrivalAirport = choose("arrivalAirport", successful,
				ProviderFlightResult::arrivalAirport, FieldPolicy.PLANNED, provenance);
		Choice<String> arrivalAirportName = chooseAirportName("arrivalAirportName", successful,
				ProviderFlightResult::arrivalAirport, ProviderFlightResult::arrivalAirportName,
				arrivalAirport.value(), provenance);
		Choice<OffsetDateTime> scheduledDeparture = choose("scheduledDeparture", successful,
				ProviderFlightResult::scheduledDeparture, FieldPolicy.PLANNED, provenance);
		Choice<OffsetDateTime> estimatedDeparture = choose("estimatedDeparture", successful,
				ProviderFlightResult::estimatedDeparture, FieldPolicy.OPERATIONAL, provenance);
		Choice<OffsetDateTime> actualDeparture = choose("actualDeparture", successful,
				ProviderFlightResult::actualDeparture, FieldPolicy.OPERATIONAL, provenance);
		Choice<OffsetDateTime> scheduledArrival = choose("scheduledArrival", successful,
				ProviderFlightResult::scheduledArrival, FieldPolicy.PLANNED, provenance);
		Choice<OffsetDateTime> estimatedArrival = choose("estimatedArrival", successful,
				ProviderFlightResult::estimatedArrival, FieldPolicy.OPERATIONAL, provenance);
		Choice<OffsetDateTime> actualArrival = choose("actualArrival", successful,
				ProviderFlightResult::actualArrival, FieldPolicy.OPERATIONAL, provenance);
		Choice<OffsetDateTime> boardingTime = choose("boardingTime", successful,
				ProviderFlightResult::boardingTime, FieldPolicy.OPERATIONAL, provenance);
		Choice<String> terminal = choose("terminal", successful, ProviderFlightResult::terminal,
				FieldPolicy.OPERATIONAL, provenance);
		Choice<String> gate = choose("gate", successful, ProviderFlightResult::gate,
				FieldPolicy.OPERATIONAL, provenance);
		Choice<String> checkIn = choose("checkInDeskOrZone", successful,
				ProviderFlightResult::checkInDeskOrZone, FieldPolicy.OPERATIONAL, provenance);
		Choice<String> bagDrop = choose("dedicatedBagDropDesk", successful,
				ProviderFlightResult::dedicatedBagDropDesk, FieldPolicy.OPERATIONAL, provenance);
		Choice<String> arrivalBaggageBelt = choose("arrivalBaggageBelt", successful,
				ProviderFlightResult::arrivalBaggageBelt, FieldPolicy.OPERATIONAL, provenance);
		Choice<String> status = choose("status", successful, ProviderFlightResult::status,
				FieldPolicy.OPERATIONAL, provenance);
		Choice<Integer> departureDelay = choose("departureDelayMinutes", successful,
				ProviderFlightResult::departureDelayMinutes, FieldPolicy.OPERATIONAL, provenance);
		Choice<Integer> arrivalDelay = choose("arrivalDelayMinutes", successful,
				ProviderFlightResult::arrivalDelayMinutes, FieldPolicy.OPERATIONAL, provenance);
		Choice<String> sourceUrl = choose("sourceUrl", successful, ProviderFlightResult::sourceUrl,
				FieldPolicy.PLANNED, provenance);
		Choice<String> codeshare = choose("codeshare", successful, ProviderFlightResult::codeshare,
				FieldPolicy.PLANNED, provenance);

		if (departureAirport.value() == null || arrivalAirport.value() == null) {
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

		boolean hasLiveOperationalSource = successful.stream().anyMatch(ProviderFlightResult::live);
		if (selectedFlightNumber.value() == null || selectedDepartureDate.value() == null) {
			throw new AllFlightProvidersUnavailableException(providerResults);
		}
		FlightResponse merged = new FlightResponse(selectedFlightNumber.value(),
				selectedDepartureDate.value(),
				departureAirport.value(), departureAirportName.value(), arrivalAirport.value(), arrivalAirportName.value(),
				scheduledDeparture.value(), estimatedDeparture.value(), actualDeparture.value(),
				scheduledArrival.value(), estimatedArrival.value(), actualArrival.value(), boardingTime.value(),
				terminal.value(), gate.value(), checkIn.value(), status.value() == null ? "UNKNOWN" : status.value(),
				departureDelayValue, arrivalDelayValue, freshestUpdatedAt, retrievedAt, sources,
				sourceUrl.value(), codeshare.value(), hasLiveOperationalSource, checkIn.value() == null, false);
		List<FlightFieldConflict> conflicts = buildProviderConflicts(provenance, retrievedAt);
		return new MultiSourceFlightSnapshot(merged, bagDrop.value(), arrivalBaggageBelt.value(),
				Map.copyOf(providerResults),
				Map.copyOf(provenance), conflicts, retrievedAt, freshestUpdatedAt);
	}

	private CompletableFuture<ProviderFlightResult> startProviderCall(OperationalFlightDataProvider provider,
			String flightNumber, LocalDate departureDate, String departureHint, String arrivalHint,
			String scheduledDepartureHint,
			boolean refresh, Duration timeout, Instant aggregateDeadline) {
		Duration bounded = boundedTimeout(timeout, aggregateDeadline);
		if (bounded.isZero() || bounded.isNegative()) {
			return CompletableFuture.completedFuture(providerTimeout(provider));
		}
		return CompletableFuture.supplyAsync(() -> validateIdentity(
				safeCall(provider, flightNumber, departureDate, departureHint, arrivalHint,
						scheduledDepartureHint, refresh), flightNumber, departureDate), executor)
				.completeOnTimeout(providerTimeout(provider), Math.max(1, bounded.toMillis()), TimeUnit.MILLISECONDS);
	}

	private ProviderFlightResult awaitRouteHint(OperationalFlightDataProvider provider,
			CompletableFuture<ProviderFlightResult> call, Instant aggregateDeadline) {
		if (provider == null || call == null) return null;
		Duration wait = boundedTimeout(ROUTE_HINT_STAGGER, aggregateDeadline);
		if (wait.isZero() || wait.isNegative()) return null;
		try {
			return call.get(Math.max(1, wait.toMillis()), TimeUnit.MILLISECONDS);
		} catch (TimeoutException exception) {
			return null;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return ProviderFlightResult.failure(provider.providerName(), "UPSTREAM_ERROR",
					provider.providerName() + "_INTERRUPTED", Instant.now());
		} catch (ExecutionException exception) {
			return ProviderFlightResult.failure(provider.providerName(), "UPSTREAM_ERROR",
					provider.providerName() + "_UNEXPECTED_ERROR", Instant.now());
		}
	}

	private void collectProviderResults(Map<String, CompletableFuture<ProviderFlightResult>> calls,
			Map<String, ProviderFlightResult> results, Instant aggregateDeadline) {
		Map<String, CompletableFuture<ProviderFlightResult>> pending = new LinkedHashMap<>(calls);
		results.keySet().forEach(pending::remove);
		Instant sufficientResultDeadline = hasSufficientSuccess(results.values())
				? Instant.now().plus(SUFFICIENT_RESULT_GRACE) : null;
		while (!pending.isEmpty()) {
			for (var iterator = pending.entrySet().iterator(); iterator.hasNext();) {
				var entry = iterator.next();
				if (!entry.getValue().isDone()) continue;
				results.put(entry.getKey(), completedResult(entry.getKey(), entry.getValue()));
				iterator.remove();
			}
			if (sufficientResultDeadline == null && hasSufficientSuccess(results.values())) {
				sufficientResultDeadline = Instant.now().plus(SUFFICIENT_RESULT_GRACE);
			}
			if (pending.isEmpty()) return;
			Instant waitDeadline = sufficientResultDeadline == null
					? aggregateDeadline : earlier(aggregateDeadline, sufficientResultDeadline);
			Duration remaining = Duration.between(Instant.now(), waitDeadline);
			if (remaining.isZero() || remaining.isNegative()) {
				boolean hasSufficientResult = hasSufficientSuccess(results.values());
				for (var entry : pending.entrySet()) {
					entry.getValue().cancel(true);
					OperationalFlightDataProvider provider = providerNamed(entry.getKey());
					results.put(entry.getKey(), hasSufficientResult
							? providerPending(provider) : providerTimeout(provider));
				}
				return;
			}
			try {
				CompletableFuture.anyOf(pending.values().toArray(CompletableFuture[]::new))
						.get(Math.max(1, remaining.toMillis()), TimeUnit.MILLISECONDS);
			} catch (TimeoutException exception) {
				// The next loop classifies unfinished providers against the active deadline.
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				for (var entry : pending.entrySet()) {
					entry.getValue().cancel(true);
					results.put(entry.getKey(), ProviderFlightResult.failure(entry.getKey(), "UPSTREAM_ERROR",
							entry.getKey() + "_INTERRUPTED", Instant.now()));
				}
				return;
			} catch (ExecutionException exception) {
				// Individual failures are converted when their completed future is drained.
			}
		}
	}

	private ProviderFlightResult completedResult(String providerName,
			CompletableFuture<ProviderFlightResult> call) {
		try {
			return call.get();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return ProviderFlightResult.failure(providerName, "UPSTREAM_ERROR",
					providerName + "_INTERRUPTED", Instant.now());
		} catch (ExecutionException | java.util.concurrent.CancellationException exception) {
			return ProviderFlightResult.failure(providerName, "UPSTREAM_ERROR",
					providerName + "_UNEXPECTED_ERROR", Instant.now());
		}
	}

	private OperationalFlightDataProvider providerNamed(String providerName) {
		return providers.stream().filter(provider -> providerName.equals(provider.providerName()))
				.findFirst().orElseThrow();
	}

	private Duration providerBudget(OperationalFlightDataProvider provider) {
		return switch (provider.providerName()) {
			case "AERODATABOX" -> aeroDataBoxTimeout;
			case "SERPAPI" -> serpApiTimeout;
			default -> aggregateTimeout;
		};
	}

	private Instant earlier(Instant first, Instant second) {
		return first.isBefore(second) ? first : second;
	}

	private ProviderFlightResult providerTimeout(OperationalFlightDataProvider provider) {
		return ProviderFlightResult.failure(provider.providerName(), "TIMEOUT",
				provider.providerName() + "_TIMEOUT", Instant.now());
	}

	private ProviderFlightResult providerPending(OperationalFlightDataProvider provider) {
		return ProviderFlightResult.failure(provider.providerName(), ProviderFlightResult.UPSTREAM_ERROR,
				provider.providerName() + "_NOT_COMPLETED", Instant.now());
	}

	private boolean hasSufficientSuccess(Iterable<ProviderFlightResult> results) {
		for (ProviderFlightResult result : results) {
			if (result != null && result.successful()
					&& result.flightNumber() != null && result.departureDate() != null
					&& result.departureAirport() != null && result.arrivalAirport() != null) return true;
		}
		return false;
	}

	private Duration boundedTimeout(Duration providerTimeout, Instant deadline) {
		Duration remaining = Duration.between(Instant.now(), deadline);
		if (remaining.isNegative()) return Duration.ZERO;
		return remaining.compareTo(providerTimeout) < 0 ? remaining : providerTimeout;
	}

	private MultiSourceFlightSnapshot awaitCombined(CompletableFuture<MultiSourceFlightSnapshot> existing) {
		try {
			return existing.get(Math.max(1, aggregateTimeout.toMillis()), TimeUnit.MILLISECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Flight lookup was interrupted", exception);
		} catch (TimeoutException exception) {
			throw new AllFlightProvidersUnavailableException(Map.of());
		} catch (ExecutionException exception) {
			if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
			throw new IllegalStateException("Flight lookup failed", exception.getCause());
		}
	}

	private boolean isFresh(CachedSnapshot cached, Instant now) {
		return cached != null && Duration.between(cached.cachedAt(), now).compareTo(cacheTtl) < 0;
	}

	private int providerPriority(OperationalFlightDataProvider provider) {
		return switch (provider.providerName()) {
			case "AERODATABOX" -> 0;
			case "SERPAPI" -> 1;
			default -> 2;
		};
	}

	private ProviderFlightResult safeCall(OperationalFlightDataProvider provider, String flightNumber,
			LocalDate departureDate, String departureHint, String arrivalHint,
			String scheduledDepartureHint, boolean refresh) {
		try {
			return provider.findFlight(flightNumber, departureDate, departureHint, arrivalHint,
					scheduledDepartureHint, refresh);
		} catch (RuntimeException exception) {
			return ProviderFlightResult.failure(provider.providerName(), "UPSTREAM_ERROR",
					provider.providerName() + "_UNEXPECTED_ERROR", Instant.now());
		}
	}

	private ProviderFlightResult validateIdentity(ProviderFlightResult result, String requestedFlightNumber,
			LocalDate requestedDepartureDate) {
		if (result == null || !result.successful()) return result;
		String actualFlightNumber = normalizeUpper(result.flightNumber());
		if (actualFlightNumber == null || actualFlightNumber.isBlank() || result.departureDate() == null) {
			return ProviderFlightResult.identityFailure(result.provider(), ProviderFlightResult.IDENTITY_UNVERIFIED,
					result.provider() + "_AGGREGATE_IDENTITY_UNVERIFIED", result.flightNumber(),
					result.departureDate(), result.retrievedAt());
		}
		String requested = normalizeUpper(requestedFlightNumber);
		boolean flightMatches = requested != null && (requested.equals(actualFlightNumber)
				|| containsExplicitCodeshare(result.codeshare(), requested));
		if (!flightMatches || !Objects.equals(requestedDepartureDate, result.departureDate())) {
			return ProviderFlightResult.identityFailure(result.provider(), ProviderFlightResult.IDENTITY_MISMATCH,
					result.provider() + "_AGGREGATE_IDENTITY_MISMATCH", result.flightNumber(),
					result.departureDate(), result.retrievedAt());
		}
		return result;
	}

	private boolean containsExplicitCodeshare(String value, String requestedFlightNumber) {
		if (value == null || requestedFlightNumber == null) return false;
		Matcher matcher = EXPLICIT_FLIGHT_NUMBER.matcher(value);
		while (matcher.find()) {
			if (requestedFlightNumber.equals(normalizeUpper(matcher.group(1) + matcher.group(2)))) return true;
		}
		return false;
	}

	private RouteHints effectiveRouteHints(String documentDeparture, String documentArrival,
			ProviderFlightResult providerResult) {
		if (documentDeparture != null && documentArrival != null) {
			return new RouteHints(documentDeparture, documentArrival);
		}
		if (providerResult != null && providerResult.successful()
				&& providerResult.departureAirport() != null && providerResult.arrivalAirport() != null) {
			return new RouteHints(providerResult.departureAirport(), providerResult.arrivalAirport());
		}
		// A single document airport can still rank Flight Result candidates, but it
		// must never be paired with the opposite endpoint from a different source.
		return new RouteHints(documentDeparture, documentArrival);
	}

	private <T> Choice<T> choose(String field, List<ProviderFlightResult> results,
			Function<ProviderFlightResult, T> getter, FieldPolicy policy,
			Map<String, FieldProvenance> provenance) {
		List<ValueCandidate<T>> candidates = results.stream()
				.filter(result -> policy != FieldPolicy.OPERATIONAL || !isScheduleOnly(result))
				.map(result -> new ValueCandidate<>(getter.apply(result), result))
				.filter(candidate -> hasValue(candidate.value()))
				.sorted(this.<T>valueCandidateComparator(field, policy).reversed())
				.toList();
		if (candidates.isEmpty()) return new Choice<>(null, null);
		ValueCandidate<T> selected = candidates.get(0);
		List<FieldCandidate> displayCandidates = candidates.stream()
				.map(candidate -> new FieldCandidate(stringValue(candidate.value()), candidate.result().provider(),
						candidate.result().sourceUpdatedAt(), candidate.result().retrievedAt(), candidate.result().live(),
						candidate.result().quality()))
				.toList();
		// Run the field-aware pairwise comparison as well as computing the
		// canonical keys. Check-in values are intentionally compatible when one
		// source only knows the area and another adds a counter range.
		List<T> values = candidates.stream().map(ValueCandidate::value).toList();
		boolean conflict = FlightFieldSemantics.hasConflict(field, values);
		FieldProvenance source = new FieldProvenance(stringValue(selected.value()), selected.result().provider(),
				selected.result().sourceUpdatedAt(), selected.result().retrievedAt(), selected.result().live(), conflict,
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
		return choose(field, matchingAirport, nameGetter, FieldPolicy.PLANNED, provenance);
	}

	private <T> Comparator<ValueCandidate<T>> valueCandidateComparator(String field, FieldPolicy policy) {
		return Comparator.<ValueCandidate<T>>comparingInt(candidate -> policy == FieldPolicy.OPERATIONAL
				? operationalQualityScore(candidate.result()) : plannedQualityScore(candidate.result()))
				.thenComparingInt(candidate -> FlightFieldSemantics.specificity(field, candidate.value()))
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
		addLiveCheckInConflict(conflicts, provenance.get("checkInDeskOrZone"), detectedAt);
		addCancellationConflict(conflicts, provenance.get("status"), detectedAt);
		addLiveTimeConflict(conflicts, "estimatedDeparture", provenance.get("estimatedDeparture"), detectedAt);
		addLiveTimeConflict(conflicts, "actualDeparture", provenance.get("actualDeparture"), detectedAt);
		addLiveTimeConflict(conflicts, "boardingTime", provenance.get("boardingTime"), detectedAt);
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
		if (liveCandidates.size() < 2 || !FlightFieldSemantics.hasConflict(field,
				liveCandidates.stream().map(FieldCandidate::value).toList())) return;
		addCriticalProviderConflict(conflicts, field, source,
				"两个实时运营来源报告了不同的" + ("terminal".equals(field) ? "航站楼" : "登机口") + "，请先向工作人员核对。",
				"Two live operational sources report different " + field + " values. Stop and confirm with staff.",
				detectedAt);
	}

	private void addLiveCheckInConflict(List<FlightFieldConflict> conflicts,
			FieldProvenance source, Instant detectedAt) {
		if (source == null) return;
		List<FieldCandidate> liveCandidates = source.candidateValues().stream()
				.filter(this::isLiveOperationalCandidate).toList();
		if (liveCandidates.size() < 2 || !FlightFieldSemantics.hasConflict("checkInDeskOrZone",
				liveCandidates.stream().map(FieldCandidate::value).toList())) return;
		addCriticalProviderConflict(conflicts, "checkInDeskOrZone", source,
				"两个实时运营来源报告了互相矛盾的值机区域或柜台范围，请先向工作人员核对。",
				"Two live operational sources report conflicting check-in areas or counter ranges. Stop and confirm with staff.",
				detectedAt);
	}

	private void addCancellationConflict(List<FlightFieldConflict> conflicts,
			FieldProvenance source, Instant detectedAt) {
		if (source == null) return;
		List<String> groups = source.candidateValues().stream().map(FieldCandidate::value)
				.map(FlightFieldSemantics::normalizeStatusGroup).filter(Objects::nonNull).distinct().toList();
		if (!groups.contains("CANCELLED") || groups.size() < 2) return;
		addCriticalProviderConflict(conflicts, "status", source,
				"一个来源报告航班已取消，另一个来源未报告取消，请先向工作人员核对。",
				"One source reports cancellation while another does not. Stop and confirm with staff.", detectedAt);
	}

	private void addLiveTimeConflict(List<FlightFieldConflict> conflicts, String field,
			FieldProvenance source, Instant detectedAt) {
		if (source == null) return;
		List<Instant> times = source.candidateValues().stream()
				.filter(this::isLiveOperationalCandidate)
				.map(FieldCandidate::value).map(FlightFieldSemantics::instant).filter(Objects::nonNull).toList();
		if (times.size() < 2) return;
		Instant earliest = times.stream().min(Comparator.naturalOrder()).orElseThrow();
		Instant latest = times.stream().max(Comparator.naturalOrder()).orElseThrow();
		if (Math.abs(ChronoUnit.MINUTES.between(earliest, latest)) < 15) return;
		boolean boarding = "boardingTime".equals(field);
		addCriticalProviderConflict(conflicts, field, source,
				"两个实时运营来源报告的" + (boarding ? "登机时间" : "起飞时间") + "相差至少 15 分钟，请先向工作人员核对。",
				"Two live operational sources report " + (boarding ? "boarding" : "departure")
						+ " times at least 15 minutes apart. Stop and confirm with staff.",
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
		return value == null ? null : value.toString().replaceAll("[\\s\\-‐‑‒–—−]", "").toUpperCase(Locale.ROOT);
	}

	private String normalizeAirportHint(String value) {
		if (value == null) return null;
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		return normalized.matches("[A-Z]{3}") ? normalized : null;
	}

	private String normalizeTimeHint(String value) {
		return value == null || value.isBlank() ? null : value.trim();
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

	private record QueryKey(String flightNumber, LocalDate departureDate,
			String departureAirportHint, String arrivalAirportHint, String scheduledDepartureHint) {
	}

	private record RouteHints(String departureAirport, String arrivalAirport) {
	}

	private record CachedSnapshot(MultiSourceFlightSnapshot snapshot, Instant cachedAt) {
	}

	private enum FieldPolicy {
		PLANNED,
		OPERATIONAL
	}
}
