package com.selene.onestepflight.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;
import com.selene.onestepflight.dto.DocumentFlightSegment;
import com.selene.onestepflight.dto.DocumentReviewIssue;
import com.selene.onestepflight.dto.DocumentType;
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
	private static final double DOCUMENT_AIRPORT_HINT_CONFIDENCE_THRESHOLD = 0.65d;
	private static final Set<String> ALLOWED_DOCUMENT_EVIDENCE_FIELDS = Set.of(
			"document_type", "flight_number", "departure_date", "departure_month_day", "scheduled_departure",
			"scheduled_arrival", "boarding_time", "departure_airport", "arrival_airport",
			"terminal", "gate", "seat");
	private static final Pattern INDEXED_LEGACY_CRITICAL_CONFLICT = Pattern.compile(
			"^segments\\[(\\d+)]\\.conflict\\.(flight_number|departure_date)$");

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
		String normalizedFlightNumber = flightService.normalize(request.flightNumber());
		BoardingPassExtractionResponse selectedDocument = selectDocumentSegment(
				request.document(), normalizedFlightNumber, request.departureDate(),
				request.documentSegmentIndex());
		rejectUnresolvedCriticalDocumentReview(request.document(), selectedDocument,
				normalizedFlightNumber, request.departureDate());
		MultiSourceFlightSnapshot snapshot = request.demo() && !isBlank(request.demoScenario()) && !request.refresh()
				? demoSnapshot(demoScenarioProvider.findInitial(normalizedFlightNumber, request.demoScenario()))
				: loadFlight(normalizedFlightNumber, request.departureDate(), request.demo(), request.demoScenario(),
						documentRouteHint(selectedDocument, "departure_airport"),
						documentRouteHint(selectedDocument, "arrival_airport"),
						selectedDocument == null ? null : selectedDocument.scheduledDeparture(), request.refresh());
		return merge(snapshot, selectedDocument, null, request.demoScenario());
	}

	private void rejectUnresolvedCriticalDocumentReview(
			BoardingPassExtractionResponse originalDocument,
			BoardingPassExtractionResponse selectedDocument,
			String normalizedFlightNumber, LocalDate requestedDate) {
		if (selectedDocument != null && hasBlockingCriticalReview(selectedDocument)) {
			throw new InvalidDocumentException("DOCUMENT_REVIEW_REQUIRED");
		}
		if (selectedDocument != null && originalDocument != null
				&& NutrientBoardingPassDocumentProcessor.IMAGE_DATA_SOURCE
						.equals(originalDocument.dataSource())
				&& !Boolean.TRUE.equals(selectedDocument.flightNumberConfirmedByUser())
				&& originalDocument.segments().stream()
						.filter(segment -> Objects.equals(normalizeDocumentFlight(segment.flightNumber()),
								normalizedFlightNumber))
						.limit(2).count() >= 2) {
			throw new InvalidDocumentException("DOCUMENT_REVIEW_REQUIRED");
		}
		if (selectedDocument != null || originalDocument == null) return;
		boolean relevantUnselectedConflict = originalDocument.reviewIssues().stream()
				.filter(DocumentReviewIssue::blocking)
				.filter(issue -> "flight_number".equals(issue.field())
						|| "departure_date".equals(issue.field()))
				.anyMatch(issue -> issue.candidates().stream().anyMatch(candidate -> {
					String value = candidate.normalizedValue();
					if ("flight_number".equals(issue.field())) {
						return Objects.equals(normalizeDocumentFlight(value), normalizedFlightNumber);
					}
					return requestedDate != null && Objects.equals(value, requestedDate.toString());
				}));
		if (relevantUnselectedConflict) {
			throw new InvalidDocumentException("DOCUMENT_REVIEW_REQUIRED");
		}
		boolean relevantLegacyConflict = originalDocument.needsReviewFields().stream()
				.filter(Objects::nonNull)
				.anyMatch(field -> relevantLegacyCriticalConflict(originalDocument, field,
						normalizedFlightNumber, requestedDate));
		if (relevantLegacyConflict) {
			throw new InvalidDocumentException("DOCUMENT_REVIEW_REQUIRED");
		}
	}

	private boolean relevantLegacyCriticalConflict(BoardingPassExtractionResponse document,
			String rawField, String normalizedFlightNumber, LocalDate requestedDate) {
		String field = rawField.trim();
		Matcher indexed = INDEXED_LEGACY_CRITICAL_CONFLICT.matcher(field);
		if (indexed.matches()) {
			int index;
			try {
				index = Integer.parseInt(indexed.group(1));
			} catch (NumberFormatException exception) {
				return false;
			}
			if (index < 0 || index >= document.segments().size()) return false;
			return legacyConflictMatches(document.segments().get(index), indexed.group(2),
					normalizedFlightNumber, requestedDate);
		}
		String criticalField = switch (field) {
			case "conflict.flight_number" -> "flight_number";
			case "conflict.departure_date" -> "departure_date";
			default -> null;
		};
		if (criticalField == null) return false;
		if (!document.segments().isEmpty()) {
			return document.segments().stream().anyMatch(segment -> legacyConflictMatches(segment,
					criticalField, normalizedFlightNumber, requestedDate));
		}
		boolean flightMatches = Objects.equals(normalizeDocumentFlight(document.flightNumber()),
				normalizedFlightNumber);
		boolean dateMatches = Objects.equals(document.departureDate(), requestedDate);
		return "flight_number".equals(criticalField) ? dateMatches && (document.flightNumber() == null
				|| flightMatches) : flightMatches && (document.departureDate() == null || dateMatches);
	}

	private boolean legacyConflictMatches(DocumentFlightSegment segment, String field,
			String normalizedFlightNumber, LocalDate requestedDate) {
		if (segment == null) return false;
		boolean flightMatches = Objects.equals(normalizeDocumentFlight(segment.flightNumber()),
				normalizedFlightNumber);
		boolean dateMatches = Objects.equals(segment.departureDate(), requestedDate);
		return "flight_number".equals(field) ? dateMatches && (segment.flightNumber() == null
				|| flightMatches) : flightMatches && (segment.departureDate() == null || dateMatches);
	}

	private boolean hasBlockingCriticalReview(BoardingPassExtractionResponse document) {
		if (NutrientBoardingPassDocumentProcessor.IMAGE_DATA_SOURCE.equals(document.dataSource())
				&& !document.segments().isEmpty()) {
			DocumentFlightSegment segment = document.segments().get(0);
			if (!Boolean.TRUE.equals(segment.flightNumberConfirmedByUser())
					&& (Boolean.TRUE.equals(segment.requiresFlightNumberConfirmation())
							|| segment.flightNumberConfidence()
									== DocumentFlightSegment.FlightNumberConfidence.LOW)) {
				return true;
			}
		}
		boolean structuredBlocking = document.reviewIssues().stream()
				.anyMatch(issue -> issue.blocking() && ("flight_number".equals(issue.field())
						|| "departure_date".equals(issue.field())));
		if (structuredBlocking) return true;
		for (String field : List.of("flight_number", "departure_date")) {
			boolean legacy = document.needsReviewFields().stream()
					.anyMatch(value -> ("conflict." + field).equals(value));
			boolean structuredReplacement = document.reviewIssues().stream()
					.anyMatch(issue -> field.equals(issue.field()));
			if (legacy && !structuredReplacement) return true;
		}
		return false;
	}

	private BoardingPassExtractionResponse selectDocumentSegment(BoardingPassExtractionResponse document,
			String normalizedFlightNumber, LocalDate requestedDate, Integer requestedSegmentIndex) {
		if (requestedSegmentIndex != null) {
			if (document == null || requestedSegmentIndex < 0
					|| requestedSegmentIndex >= document.segments().size()) {
				throw new InvalidDocumentException("DOCUMENT_SEGMENT_SELECTION_INVALID");
			}
			DocumentFlightSegment requestedSegment = document.segments().get(requestedSegmentIndex);
			if (!documentSegmentMatchesRequest(requestedSegment, normalizedFlightNumber, requestedDate)) {
				throw new InvalidDocumentException("DOCUMENT_SEGMENT_SELECTION_INVALID");
			}
			return selectedDocumentSegment(document, requestedSegment, requestedSegmentIndex);
		}
		if (document == null) return null;
		if (document.segments().size() <= 1) {
			return documentMatchesRequest(document, normalizedFlightNumber, requestedDate) ? document : null;
		}
		DocumentFlightSegment selected = null;
		int selectedScore = Integer.MIN_VALUE;
		int selectedIndex = -1;
		for (int index = 0; index < document.segments().size(); index++) {
			DocumentFlightSegment segment = document.segments().get(index);
			if (!Objects.equals(normalizeDocumentFlight(segment.flightNumber()), normalizedFlightNumber)) continue;
			if (!Objects.equals(segment.departureDate(), requestedDate)) continue;
			int score = documentSegmentScore(segment, normalizedFlightNumber, requestedDate);
			if (score > selectedScore) {
				selected = segment;
				selectedScore = score;
				selectedIndex = index;
			}
		}
		if (selected == null || hasAmbiguousBestDocumentRoute(document, normalizedFlightNumber,
				requestedDate, selectedScore)) return null;
		return selectedDocumentSegment(document, selected, selectedIndex);
	}

	private BoardingPassExtractionResponse selectedDocumentSegment(BoardingPassExtractionResponse document,
			DocumentFlightSegment selected, int selectedIndex) {
		Map<String, ExtractedField> evidence = new LinkedHashMap<>(document.fields());
		evidence.putAll(selected.fields());
		String prefix = "segments[" + selectedIndex + "].";
		List<String> reviewFields = document.needsReviewFields().stream()
				.filter(Objects::nonNull)
				.filter(field -> !field.startsWith("segments[") || field.startsWith(prefix))
				.map(field -> field.startsWith(prefix) ? field.substring(prefix.length()) : field)
				.toList();
		List<DocumentReviewIssue> reviewIssues = document.reviewIssues().stream()
				.filter(issue -> issue.segmentIndex() == null || issue.segmentIndex() == selectedIndex)
				.map(issue -> new DocumentReviewIssue(0, issue.field(), issue.kind(), issue.blocking(),
						issue.candidates().stream()
								.map(candidate -> new com.selene.onestepflight.dto.DocumentFieldCandidate(
										candidate.field(), candidate.rawValue(), candidate.normalizedValue(),
										candidate.source(), candidate.confidence(), candidate.page(),
										candidate.boundingBox(), candidate.nearbyAnchor(), 0,
										candidate.disposition(), candidate.reason()))
								.toList(),
						issue.selectedValue(), issue.reason()))
				.toList();
		return new BoardingPassExtractionResponse(document.documentType(), selected.flightNumber(),
				selected.departureDate(), selected.departureMonthDay(), selected.flightNumberConfidence(),
				selected.requiresFlightNumberConfirmation(), selected.flightNumberConfirmedByUser(),
				selected.dateSource(), selected.scheduledDeparture(), selected.scheduledArrival(),
				selected.boardingTime(), selected.departureAirport(), selected.arrivalAirport(),
				selected.terminal(), selected.gate(), selected.seat(), false, false,
				evidence, List.of(selected), reviewFields, reviewIssues, document.dataSource(),
				document.extractedAt());
	}

	private boolean documentSegmentMatchesRequest(DocumentFlightSegment segment,
			String normalizedFlightNumber, LocalDate requestedDate) {
		return segment != null
				&& Objects.equals(normalizeDocumentFlight(segment.flightNumber()), normalizedFlightNumber)
				&& Objects.equals(segment.departureDate(), requestedDate);
	}

	private boolean hasAmbiguousBestDocumentRoute(BoardingPassExtractionResponse document,
			String flightNumber, LocalDate requestedDate, int selectedScore) {
		return document.segments().stream()
				.filter(segment -> Objects.equals(normalizeDocumentFlight(segment.flightNumber()), flightNumber))
				.filter(segment -> Objects.equals(segment.departureDate(), requestedDate))
				.filter(segment -> documentSegmentScore(segment, flightNumber, requestedDate) == selectedScore)
				.map(segment -> normalizeAirport(segment.departureAirport()) + ">"
						+ normalizeAirport(segment.arrivalAirport()))
				.distinct().limit(2).count() > 1;
	}

	private boolean documentMatchesRequest(BoardingPassExtractionResponse document,
			String flightNumber, LocalDate departureDate) {
		return document != null
				&& Objects.equals(normalizeDocumentFlight(document.flightNumber()), flightNumber)
				&& Objects.equals(document.departureDate(), departureDate);
	}

	private int documentSegmentScore(DocumentFlightSegment segment, String flightNumber, LocalDate requestedDate) {
		if (segment == null) return Integer.MIN_VALUE;
		int score = 0;
		if (Objects.equals(normalizeDocumentFlight(segment.flightNumber()), flightNumber)) score += 100;
		if (segment.departureDate() != null && requestedDate != null) {
			long days = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(
					segment.departureDate(), requestedDate));
			if (days == 0) score += 30;
			else if (days == 1) score += 10;
		}
		if (normalizeAirport(segment.departureAirport()) != null) score += 2;
		if (normalizeAirport(segment.arrivalAirport()) != null) score += 2;
		if (!isBlank(segment.scheduledDeparture())) score += 1;
		return score;
	}

	private String normalizeDocumentFlight(String value) {
		return value == null ? null : Normalizer.normalize(value, Normalizer.Form.NFKC)
				.replaceAll("[\\p{Z}\\s\\-‐‑‒–—]", "").toUpperCase(Locale.ROOT);
	}

	/**
	 * A document airport is only a route hint, never part of the primary flight
	 * identity. Keep uncertain values out of provider filters while they have an
	 * outstanding review marker or low-confidence/mismatched evidence.
	 */
	String documentRouteHint(BoardingPassExtractionResponse document, String fieldName) {
		if (document == null) return null;
		String value = switch (fieldName) {
			case "departure_airport" -> document.departureAirport();
			case "arrival_airport" -> document.arrivalAirport();
			default -> null;
		};
		String normalized = normalizeAirport(value);
		if (normalized == null || documentFieldNeedsReview(document, fieldName)) return null;

		ExtractedField evidence = document.fields().get(fieldName);
		if (evidence != null && (!Double.isFinite(evidence.confidence())
				|| evidence.confidence() < DOCUMENT_AIRPORT_HINT_CONFIDENCE_THRESHOLD
				|| !normalized.equals(normalizeAirport(evidence.value())))) {
			return null;
		}
		return normalized;
	}

	String documentScheduledDepartureHint(BoardingPassExtractionResponse document) {
		if (document == null || isBlank(document.scheduledDeparture())
				|| documentFieldNeedsReview(document, "scheduled_departure")) return null;
		String value = document.scheduledDeparture().trim();
		ExtractedField evidence = document.fields().get("scheduled_departure");
		if (evidence != null && (!Double.isFinite(evidence.confidence())
				|| evidence.confidence() < DOCUMENT_AIRPORT_HINT_CONFIDENCE_THRESHOLD
				|| evidence.value() == null || !value.equals(evidence.value().trim()))) {
			return null;
		}
		return value;
	}

	private boolean documentFieldNeedsReview(BoardingPassExtractionResponse document, String fieldName) {
		boolean structured = document.reviewIssues().stream()
				.anyMatch(issue -> fieldName.equalsIgnoreCase(issue.field())
						|| ("scheduled_departure".equals(fieldName)
								&& "departure_time".equals(issue.field())));
		return structured || document.needsReviewFields().stream()
				.filter(Objects::nonNull)
				.map(name -> name.substring(name.lastIndexOf('.') + 1))
				.anyMatch(fieldName::equalsIgnoreCase);
	}

	private String normalizeAirport(String value) {
		if (isBlank(value)) return null;
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		return normalized.matches("[A-Z]{3}") ? normalized : null;
	}

	public ResolvedFlightContext refresh(ResolvedFlightContext previous, boolean refreshRequested) {
		if (!refreshRequested && "DOCUMENT_FALLBACK".equals(previous.liveDataSource())) {
			return sanitizeDocumentFallback(previous);
		}
		MultiSourceFlightSnapshot snapshot = loadFlight(previous.flightNumber(), previous.departureDate(), previous.demo(),
				previous.demoScenario(), previous.departureAirport(), previous.arrivalAirport(),
				previous.documentScheduledDeparture() == null
						? null : previous.documentScheduledDeparture().toString(), refreshRequested);
		return merge(snapshot, null, previous, previous.demoScenario());
	}

	private MultiSourceFlightSnapshot loadFlight(String flightNumber, LocalDate departureDate, boolean demo,
			String demoScenario, String departureHint, String arrivalHint,
			String scheduledDepartureHint, boolean refresh) {
		if (demo && !isBlank(demoScenario)) {
			return demoSnapshot(demoScenarioProvider.find(flightNumber, demoScenario, refresh));
		}
		if (demo) return demoSnapshot(flightService.getFlight(flightNumber, departureDate, true, refresh));
		return multiSourceFlightService.findFlight(flightNumber, departureDate, departureHint, arrivalHint,
				scheduledDepartureHint, refresh);
	}

	private MultiSourceFlightSnapshot demoSnapshot(FlightResponse flight) {
		return new MultiSourceFlightSnapshot(flight, null, null, Map.of(), Map.of(), List.of(),
				flight.dataRetrievedAt(), flight.lastUpdated());
	}

	private ResolvedFlightContext sanitizeDocumentFallback(ResolvedFlightContext previous) {
		Instant retrievedAt = Instant.now();
		FlightResponse flight = documentFallbackFlight(flightService.normalize(previous.flightNumber()),
				previous.departureDate(), normalizeAirport(previous.departureAirport()),
				normalizeAirport(previous.arrivalAirport()));
		MultiSourceFlightSnapshot snapshot = new MultiSourceFlightSnapshot(flight, null, null,
				Map.of(), Map.of(), List.of(), retrievedAt, null);
		return merge(snapshot, null, previous, previous.demoScenario());
	}

	private FlightResponse documentFallbackFlight(String flightNumber, LocalDate departureDate,
			String departureAirport, String arrivalAirport) {
		return new FlightResponse(
				flightNumber,
				departureDate,
				departureAirport,
				departureAirport,
				arrivalAirport,
				arrivalAirport,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				0,
				0,
				null,
				null,
				"DOCUMENT_FALLBACK",
				null,
				null,
				false,
				true,
				false);
	}

	private OffsetDateTime parseOffsetDateTime(String value) {
		if (isBlank(value)) return null;
		try {
			return OffsetDateTime.parse(value);
		} catch (DateTimeParseException ignored) {
			// A local document time has no trustworthy UTC offset without live data.
			return null;
		}
	}

	private ResolvedFlightContext merge(MultiSourceFlightSnapshot snapshot, BoardingPassExtractionResponse document,
			ResolvedFlightContext previous, String demoScenario) {
		FlightResponse liveFlight = snapshot.flight();
		boolean documentFallback = "DOCUMENT_FALLBACK".equals(liveFlight.dataSource());
		if (document != null && !documentMatchesFlight(document, liveFlight)) document = null;
		boolean retainPreviousDocument = previous != null && resolvedIdentityMatchesFlight(previous, liveFlight);
		Instant detectedAt = Instant.now();
		ZoneOffset departureOffset = liveFlight.scheduledDeparture() == null
				? ZoneOffset.UTC : liveFlight.scheduledDeparture().getOffset();
		Map<String, ExtractedField> evidence = document != null
				? safeEvidence(document.fields())
				: !retainPreviousDocument ? Map.of() : safeEvidence(previous.documentEvidence());
		String documentType = document != null ? document.documentType().name()
				: previous == null ? "MANUAL_ENTRY" : safeDocumentType(previous.documentType());
		boolean boardingPassDetected = false;
		String documentTerminal = document != null ? document.terminal()
				: !retainPreviousDocument ? null : previous.documentTerminal();
		String documentGate = document != null ? document.gate()
				: !retainPreviousDocument ? null : previous.documentGate();
		String seat = document != null ? document.seat() : !retainPreviousDocument ? null : previous.seat();
		OffsetDateTime documentScheduled = documentFallback
				? document != null ? parseOffsetDateTime(document.scheduledDeparture())
						: !retainPreviousDocument ? null : previous.documentScheduledDeparture()
				: document != null
				? parseDocumentDateTime(document.scheduledDeparture(), document.departureDate(), departureOffset)
				: !retainPreviousDocument ? null : previous.documentScheduledDeparture();
		OffsetDateTime documentBoarding = documentFallback
				? document != null ? parseOffsetDateTime(document.boardingTime())
						: !retainPreviousDocument ? null : previous.documentBoardingTime()
				: document != null
				? parseDocumentDateTime(document.boardingTime(), document.departureDate(), departureOffset)
				: !retainPreviousDocument ? null : previous.documentBoardingTime();
		OffsetDateTime boardingTime = documentBoarding != null ? documentBoarding : liveFlight.boardingTime();
		OffsetDateTime effectiveDeparture = firstNonNull(liveFlight.actualDeparture(),
				liveFlight.estimatedDeparture(), liveFlight.scheduledDeparture());
		List<FlightFieldConflict> conflicts = new ArrayList<>(snapshot.providerConflicts());
		if (document != null && !documentFallback) conflicts.addAll(findConflicts(document, liveFlight, detectedAt));
		else if (previous != null && previous.conflicts() != null) {
			conflicts.addAll(previous.conflicts().stream().filter(FlightFieldConflict::blocking).toList());
		}
		if (previous != null && "DOCUMENT_FALLBACK".equals(previous.liveDataSource()) && !documentFallback) {
			addConflict(conflicts, "departureDate", stringValue(previous.departureDate()),
					stringValue(liveFlight.departureDate()), true, detectedAt);
			addConflict(conflicts, "departureAirport", previous.departureAirport(),
					liveFlight.departureAirport(), true, detectedAt);
			addConflict(conflicts, "arrivalAirport", previous.arrivalAirport(),
					liveFlight.arrivalAirport(), true, detectedAt);
		}
		List<FlightChange> changes = documentFallback ? List.of() : previous == null
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
				documentFallback ? null : firstNonBlank(liveFlight.terminal(), documentTerminal),
				documentFallback ? null : firstNonBlank(liveFlight.gate(), documentGate),
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
						? !retainPreviousDocument ? null : safeDocumentDataSource(previous.documentDataSource())
						: safeDocumentDataSource(document.dataSource()),
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
				liveFlight.flightNumber(), false, detectedAt);
		addConflict(conflicts, "departureDate", stringValue(document.departureDate()),
				stringValue(liveFlight.departureDate()), false, detectedAt);
		addConflict(conflicts, "departureAirport", document.departureAirport(),
				liveFlight.departureAirport(), false, detectedAt);
		addConflict(conflicts, "arrivalAirport", document.arrivalAirport(),
				liveFlight.arrivalAirport(), false, detectedAt);
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

	Map<String, ExtractedField> safeEvidence(Map<String, ExtractedField> fields) {
		if (fields == null || fields.isEmpty()) return Map.of();
		Map<String, ExtractedField> safe = new LinkedHashMap<>();
		fields.forEach((name, field) -> {
			if (!ALLOWED_DOCUMENT_EVIDENCE_FIELDS.contains(name) || field == null) return;
			String value = safeEvidenceValue(name, field.value());
			if (field.value() != null && value == null) return;
			double confidence = Double.isFinite(field.confidence())
					? Math.max(0.0d, Math.min(1.0d, field.confidence())) : 0.0d;
			safe.put(name, new ExtractedField(value, confidence, field.page(), field.boundingBox(), null));
		});
		return Map.copyOf(safe);
	}

	private String safeEvidenceValue(String name, String value) {
		if (value == null) return null;
		String trimmed = value.trim();
		if (trimmed.isEmpty() || trimmed.length() > 64) return null;
		return switch (name) {
			case "document_type" -> DocumentType.fromProviderValue(trimmed).name();
			case "flight_number" -> {
				String normalized = normalizeDocumentFlight(trimmed);
				yield normalized != null && normalized.matches("[A-Z0-9]{2,3}[0-9]{1,4}[A-Z]?")
						? normalized : null;
			}
			case "departure_airport", "arrival_airport" -> normalizeAirport(trimmed);
			case "departure_date", "departure_month_day" ->
					trimmed.matches("[\\p{L}\\p{N} ./\\-]+") ? trimmed : null;
			case "scheduled_departure", "scheduled_arrival", "boarding_time" ->
					trimmed.matches("[0-9T:+\\-Z ]+") ? trimmed : null;
			case "terminal", "gate", "seat" ->
					trimmed.length() <= 24 && trimmed.matches("[\\p{L}\\p{N} ./\\-]+") ? trimmed : null;
			default -> null;
		};
	}

	private boolean documentMatchesFlight(BoardingPassExtractionResponse document, FlightResponse flight) {
		return document != null && flight != null
				&& Objects.equals(normalizeDocumentFlight(document.flightNumber()),
						normalizeDocumentFlight(flight.flightNumber()))
				&& Objects.equals(document.departureDate(), flight.departureDate())
				&& airportHintMatches(document.departureAirport(), flight.departureAirport())
				&& airportHintMatches(document.arrivalAirport(), flight.arrivalAirport());
	}

	private boolean resolvedIdentityMatchesFlight(ResolvedFlightContext previous, FlightResponse flight) {
		return previous != null && flight != null
				&& Objects.equals(normalizeDocumentFlight(previous.flightNumber()),
						normalizeDocumentFlight(flight.flightNumber()))
				&& Objects.equals(previous.departureDate(), flight.departureDate())
				&& airportHintMatches(previous.departureAirport(), flight.departureAirport())
				&& airportHintMatches(previous.arrivalAirport(), flight.arrivalAirport());
	}

	private boolean airportHintMatches(String documentValue, String providerValue) {
		String documentAirport = normalizeAirport(documentValue);
		return documentAirport == null || Objects.equals(documentAirport, normalizeAirport(providerValue));
	}

	private String safeDocumentType(String value) {
		return "MANUAL_ENTRY".equals(value) ? value : DocumentType.fromProviderValue(value).name();
	}

	private String safeDocumentDataSource(String value) {
		return ("NUTRIENT_DWS_DATA_EXTRACTION".equals(value)
				|| NutrientBoardingPassDocumentProcessor.IMAGE_DATA_SOURCE.equals(value)) ? value : null;
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
