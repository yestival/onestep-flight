package com.selene.onestepflight.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;
import com.selene.onestepflight.dto.ExtractedField;
import com.selene.onestepflight.dto.FieldCandidate;
import com.selene.onestepflight.dto.FieldProvenance;
import com.selene.onestepflight.dto.FlightChange;
import com.selene.onestepflight.dto.FlightFieldConflict;
import com.selene.onestepflight.dto.FlightResponse;
import com.selene.onestepflight.dto.FlightTimeContext;
import com.selene.onestepflight.dto.ResolveFlightRequest;
import com.selene.onestepflight.dto.ResolvedFlightContext;

@Service
public class ResolvedFlightService {

	private final FlightService flightService;
	private final MultiSourceFlightService multiSourceFlightService;
	private final DemoScenarioFlightDataProvider demoScenarioProvider;
	private final FlightTimeService flightTimeService;

	public ResolvedFlightService(FlightService flightService, MultiSourceFlightService multiSourceFlightService,
			DemoScenarioFlightDataProvider demoScenarioProvider,
			FlightTimeService flightTimeService) {
		this.flightService = flightService;
		this.multiSourceFlightService = multiSourceFlightService;
		this.demoScenarioProvider = demoScenarioProvider;
		this.flightTimeService = flightTimeService;
	}

	public ResolvedFlightContext resolve(ResolveFlightRequest request) {
		if (request.document() != null && request.document().segments().size() > 1) {
			throw new InvalidDocumentException("DOCUMENT_SEGMENT_SELECTION_REQUIRED");
		}
		String normalizedFlightNumber = flightService.normalize(request.flightNumber());
		MultiSourceFlightSnapshot snapshot = request.demo() && !isBlank(request.demoScenario()) && !request.refresh()
				? demoSnapshot(demoScenarioProvider.findInitial(normalizedFlightNumber, request.demoScenario()))
				: loadFlight(normalizedFlightNumber, request.departureDate(), request.demo(), request.demoScenario(),
						request.document() == null ? null : request.document().departureAirport(),
						request.document() == null ? null : request.document().arrivalAirport(), request.refresh());
		return merge(snapshot, request.document(), null, request.demoScenario());
	}

	public ResolvedFlightContext refresh(ResolvedFlightContext previous, boolean refreshRequested) {
		MultiSourceFlightSnapshot snapshot = loadFlight(previous.flightNumber(), previous.departureDate(), previous.demo(),
				previous.demoScenario(), previous.departureAirport(), previous.arrivalAirport(), refreshRequested);
		return merge(snapshot, null, previous, previous.demoScenario());
	}

	private MultiSourceFlightSnapshot loadFlight(String flightNumber, LocalDate departureDate, boolean demo,
			String demoScenario, String departureHint, String arrivalHint, boolean refresh) {
		if (demo && !isBlank(demoScenario)) {
			return demoSnapshot(demoScenarioProvider.find(flightNumber, demoScenario, refresh));
		}
		if (demo) return demoSnapshot(flightService.getFlight(flightNumber, departureDate, true, refresh));
		return multiSourceFlightService.findFlight(flightNumber, departureDate, departureHint, arrivalHint, refresh);
	}

	private MultiSourceFlightSnapshot demoSnapshot(FlightResponse flight) {
		return new MultiSourceFlightSnapshot(flight, null, null, Map.of(), Map.of(), List.of(),
				flight.dataRetrievedAt(), flight.lastUpdated());
	}

	private ResolvedFlightContext merge(MultiSourceFlightSnapshot snapshot, BoardingPassExtractionResponse document,
			ResolvedFlightContext previous, String demoScenario) {
		FlightResponse liveFlight = snapshot.flight();
		Instant detectedAt = Instant.now();
		ZoneOffset departureOffset = liveFlight.scheduledDeparture() == null
				? ZoneOffset.UTC : liveFlight.scheduledDeparture().getOffset();
		Map<String, ExtractedField> evidence = document != null
				? safeEvidence(document.fields())
				: previous == null ? Map.of() : safeEvidence(previous.documentEvidence());
		String documentType = document != null ? document.documentType().name()
				: previous == null ? "MANUAL_ENTRY" : previous.documentType();
		boolean boardingPassDetected = document != null ? document.boardingPassDetected()
				: previous != null && previous.boardingPassDetected();
		String documentTerminal = document != null ? document.terminal()
				: previous == null ? null : previous.documentTerminal();
		String documentGate = document != null ? document.gate()
				: previous == null ? null : previous.documentGate();
		String seat = document != null ? document.seat() : previous == null ? null : previous.seat();
		OffsetDateTime documentScheduled = document != null
				? parseDocumentDateTime(document.scheduledDeparture(), document.departureDate(), departureOffset)
				: previous == null ? null : previous.documentScheduledDeparture();
		OffsetDateTime documentBoarding = document != null
				? parseDocumentDateTime(document.boardingTime(), document.departureDate(), departureOffset)
				: previous == null ? null : previous.documentBoardingTime();
		OffsetDateTime boardingTime = documentBoarding != null ? documentBoarding : liveFlight.boardingTime();
		OffsetDateTime effectiveDeparture = firstNonNull(liveFlight.actualDeparture(),
				liveFlight.estimatedDeparture(), liveFlight.scheduledDeparture());
		List<FlightFieldConflict> conflicts = new ArrayList<>(snapshot.providerConflicts());
		if (document != null) conflicts.addAll(findConflicts(document, liveFlight, detectedAt));
		else if (previous != null && previous.conflicts() != null) {
			conflicts.addAll(previous.conflicts().stream().filter(FlightFieldConflict::blocking).toList());
		}
		List<FlightChange> changes = previous == null
				? findDocumentChanges(documentTerminal, documentGate, liveFlight, detectedAt)
				: findLiveChanges(previous, liveFlight, effectiveDeparture, detectedAt);
		FlightTimeContext timeContext = flightTimeService.build(liveFlight, boardingTime);
		Map<String, FieldProvenance> fieldProvenance = new LinkedHashMap<>(snapshot.fieldProvenance());
		addDocumentProvenance(fieldProvenance, "terminal", documentTerminal, document);
		addDocumentProvenance(fieldProvenance, "gate", documentGate, document);

		return new ResolvedFlightContext(
				liveFlight.flightNumber(),
				liveFlight.flightNumber(),
				liveFlight.departureDate(),
				documentType,
				boardingPassDetected,
				liveFlight.departureAirport(),
				liveFlight.departureAirportName(),
				liveFlight.arrivalAirport(),
				liveFlight.arrivalAirportName(),
				documentScheduled,
				documentBoarding,
				liveFlight.scheduledDeparture(),
				liveFlight.estimatedDeparture(),
				liveFlight.actualDeparture(),
				effectiveDeparture,
				liveFlight.scheduledArrival(),
				liveFlight.estimatedArrival(),
				liveFlight.actualArrival(),
				documentTerminal,
				documentGate,
				firstNonBlank(liveFlight.terminal(), documentTerminal),
				firstNonBlank(liveFlight.gate(), documentGate),
				seat,
				liveFlight.checkInCounter(),
				liveFlight.checkInCounter(),
				snapshot.dedicatedBagDropDesk(),
				snapshot.arrivalBaggageBelt(),
				liveFlight.status(),
				liveFlight.delayMinutes(),
				liveFlight.arrivalDelayMinutes(),
				liveFlight.lastUpdated(),
				liveFlight.dataRetrievedAt(),
				snapshot.retrievedAt(),
				snapshot.freshestSourceUpdatedAt(),
				document == null
						? previous == null ? null : previous.documentDataSource()
						: document.dataSource(),
				liveFlight.dataSource(),
				liveFlight.sourceUrl(),
				liveFlight.codeshare(),
				liveFlight.live(),
				liveFlight.demo(),
				demoScenario,
				liveFlight.checkInCounterNeedsConfirmation(),
				timeContext.stale(),
				snapshot.providerResults(),
				Map.copyOf(fieldProvenance),
				evidence,
				List.copyOf(conflicts),
				conflicts.stream().anyMatch(FlightFieldConflict::blocking),
				List.copyOf(changes),
				timeContext);
	}

	private void addDocumentProvenance(Map<String, FieldProvenance> provenance, String field,
			String value, BoardingPassExtractionResponse document) {
		if (isBlank(value) || provenance.containsKey(field) || document == null) return;
		Instant retrievedAt = document.extractedAt();
		FieldCandidate candidate = new FieldCandidate(value, document.dataSource(), null, retrievedAt, false,
				"DOCUMENT_EXTRACTION");
		provenance.put(field, new FieldProvenance(value, document.dataSource(), null, retrievedAt, false,
				false, List.of(candidate)));
	}

	private List<FlightFieldConflict> findConflicts(BoardingPassExtractionResponse document,
			FlightResponse liveFlight, Instant detectedAt) {
		if (document == null) return List.of();
		List<FlightFieldConflict> conflicts = new ArrayList<>();
		addConflict(conflicts, "flightNumber", document.flightNumber(),
				liveFlight.flightNumber(), true, detectedAt);
		addConflict(conflicts, "departureDate", stringValue(document.departureDate()),
				stringValue(liveFlight.departureDate()), true, detectedAt);
		addConflict(conflicts, "departureAirport", document.departureAirport(),
				liveFlight.departureAirport(), true, detectedAt);
		addConflict(conflicts, "arrivalAirport", document.arrivalAirport(),
				liveFlight.arrivalAirport(), true, detectedAt);
		return conflicts;
	}

	private void addConflict(List<FlightFieldConflict> conflicts, String field, String documentValue,
			String liveValue, boolean blocking, Instant detectedAt) {
		if (FlightFieldSemantics.equivalent(field, documentValue, liveValue)) return;
		conflicts.add(new FlightFieldConflict(field, documentValue, liveValue, blocking,
				"文件中的" + field + "与实时航班不一致。请暂停并向工作人员核对。",
				"The document " + field + " does not match the live flight. Stop and confirm with staff.",
				detectedAt));
	}

	List<FlightChange> findDocumentChanges(String documentTerminal, String documentGate,
			FlightResponse liveFlight, Instant detectedAt) {
		List<FlightChange> changes = new ArrayList<>();
		addSemanticChange(changes, "terminal", "TERMINAL_CHANGED", documentTerminal, liveFlight.terminal(), true,
				"实时航站楼与文件不同", "Live terminal differs from the document", detectedAt);
		addSemanticChange(changes, "gate", "GATE_CHANGED", documentGate, liveFlight.gate(), true,
				"实时登机口与文件不同", "Live gate differs from the document", detectedAt);
		return changes;
	}

	private List<FlightChange> findLiveChanges(ResolvedFlightContext previous, FlightResponse flight,
			OffsetDateTime effectiveDeparture, Instant detectedAt) {
		List<FlightChange> changes = new ArrayList<>();
		addSemanticChange(changes, "terminal", "TERMINAL_CHANGED", previous.terminal(), flight.terminal(), true,
				"航站楼发生变化", "Terminal changed", detectedAt);
		addSemanticChange(changes, "gate", "GATE_CHANGED", previous.gate(), flight.gate(), true,
				"登机口发生变化", "Gate changed", detectedAt);
		addSemanticChange(changes, "status", "STATUS_CHANGED", previous.status(), flight.status(),
				"CANCELLED".equals(normalizeStatusGroup(previous.status()))
						!= "CANCELLED".equals(normalizeStatusGroup(flight.status())),
				"航班状态发生变化", "Flight status changed", detectedAt);
		addDepartureTimeChange(changes, previous.effectiveDeparture(), effectiveDeparture, detectedAt);
		addChange(changes, "DELAY_CHANGED", String.valueOf(previous.delayMinutes()),
				String.valueOf(flight.delayMinutes()), flight.delayMinutes() >= 45,
				"延误时间发生变化", "Delay changed", detectedAt);
		return changes;
	}

	private void addChange(List<FlightChange> changes, String type, String oldValue, String newValue,
			boolean critical, String titleZh, String titleEn, Instant detectedAt) {
		if (isBlank(oldValue) || isBlank(newValue) || Objects.equals(oldValue, newValue)) return;
		changes.add(new FlightChange(type, oldValue, newValue, critical, titleZh, titleEn, detectedAt));
	}

	private void addSemanticChange(List<FlightChange> changes, String field, String type,
			String oldValue, String newValue, boolean critical,
			String titleZh, String titleEn, Instant detectedAt) {
		if (FlightFieldSemantics.equivalent(field, oldValue, newValue)) return;
		changes.add(new FlightChange(type, oldValue, newValue, critical, titleZh, titleEn, detectedAt));
	}

	private void addDepartureTimeChange(List<FlightChange> changes, OffsetDateTime oldValue,
			OffsetDateTime newValue, Instant detectedAt) {
		if (oldValue == null || newValue == null) return;
		long differenceMinutes = Math.abs(Duration.between(oldValue.toInstant(), newValue.toInstant()).toMinutes());
		if (differenceMinutes < 5) return;
		changes.add(new FlightChange("DEPARTURE_TIME_CHANGED", oldValue.toString(), newValue.toString(),
				differenceMinutes >= 15, "预计起飞时间发生变化", "Expected departure time changed", detectedAt));
	}

	private String normalizeStatusGroup(String value) {
		return FlightFieldSemantics.normalizeStatusGroup(value);
	}

	private OffsetDateTime parseDocumentDateTime(String value, LocalDate date, ZoneOffset offset) {
		if (isBlank(value)) return null;
		try {
			return OffsetDateTime.parse(value);
		} catch (DateTimeParseException ignored) {
			// Nutrient often returns local document times without an offset.
		}
		try {
			return LocalDateTime.parse(value).atOffset(offset);
		} catch (DateTimeParseException ignored) {
			// A printed boarding time may contain only HH:mm.
		}
		try {
			return date == null ? null : date.atTime(LocalTime.parse(value)).atOffset(offset);
		} catch (DateTimeParseException ignored) {
			return null;
		}
	}

	private Map<String, ExtractedField> safeEvidence(Map<String, ExtractedField> fields) {
		return fields == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(fields));
	}

	private String firstNonBlank(String first, String second) {
		return isBlank(first) ? second : first;
	}

	private String stringValue(Object value) {
		return value == null ? null : value.toString();
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	@SafeVarargs
	private final <T> T firstNonNull(T... values) {
		for (T value : values) if (value != null) return value;
		return null;
	}
}
