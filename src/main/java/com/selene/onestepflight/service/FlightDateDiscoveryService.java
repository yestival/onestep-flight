package com.selene.onestepflight.service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;
import com.selene.onestepflight.dto.DocumentFlightSegment;
import com.selene.onestepflight.dto.ExtractedField;
import com.selene.onestepflight.dto.FlightDateDiscoveryRequest;
import com.selene.onestepflight.dto.FlightDateDiscoveryResponse;

@Service
public class FlightDateDiscoveryService {

	private final FlightDateDiscoveryProvider provider;
	private final ResolvedFlightService resolvedFlightService;

	public FlightDateDiscoveryService(FlightDateDiscoveryProvider provider,
			ResolvedFlightService resolvedFlightService) {
		this.provider = provider;
		this.resolvedFlightService = resolvedFlightService;
	}

	/**
	 * Date discovery is deliberately fail-closed. Any invalid, ambiguous or
	 * unavailable result asks the UI for the missing date instead of exposing a
	 * provider error or guessing an itinerary.
	 */
	public FlightDateDiscoveryResponse discover(FlightDateDiscoveryRequest request) {
		try {
			if (request == null) return FlightDateDiscoveryResponse.required();
			String flightNumber = normalizeFlight(request.flightNumber());
			BoardingPassExtractionResponse selected = selectSegment(
					request.document(), request.segmentIndex(), flightNumber);
			if (selected == null) return FlightDateDiscoveryResponse.required();

			String departureHint = resolvedFlightService.documentRouteHint(selected, "departure_airport");
			String arrivalHint = resolvedFlightService.documentRouteHint(selected, "arrival_airport");
			String scheduledHint = resolvedFlightService.documentScheduledDepartureHint(selected);
			LocalDate discovered = provider.discoverDepartureDate(
					flightNumber, departureHint, arrivalHint, scheduledHint);
			return discovered == null ? FlightDateDiscoveryResponse.required()
					: FlightDateDiscoveryResponse.found(discovered);
		} catch (RuntimeException exception) {
			return FlightDateDiscoveryResponse.required();
		}
	}

	private BoardingPassExtractionResponse selectSegment(BoardingPassExtractionResponse document,
			Integer segmentIndex, String requestedFlightNumber) {
		if (document == null || segmentIndex == null || segmentIndex < 0
				|| segmentIndex >= document.segments().size()) return null;
		DocumentFlightSegment segment = document.segments().get(segmentIndex);
		if (!Objects.equals(normalizeFlight(segment.flightNumber()), requestedFlightNumber)) return null;

		Map<String, ExtractedField> evidence = new LinkedHashMap<>(document.fields());
		evidence.putAll(segment.fields());
		String prefix = "segments[" + segmentIndex + "].";
		List<String> reviewFields = document.needsReviewFields().stream()
				.filter(Objects::nonNull)
				.filter(field -> !field.startsWith("segments[") || field.startsWith(prefix))
				.map(field -> field.startsWith(prefix) ? field.substring(prefix.length()) : field)
				.toList();
		return new BoardingPassExtractionResponse(document.documentType(), null, null,
				null, null, null, null, null, null, null, null,
				false, false, Map.copyOf(evidence), List.of(segment), reviewFields,
				document.dataSource(), document.extractedAt());
	}

	private String normalizeFlight(String value) {
		if (value == null) throw new IllegalArgumentException("flight number required");
		String normalized = value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
		if (!normalized.matches("[A-Z0-9]{2,3}[0-9]{1,4}[A-Z]?")) {
			throw new IllegalArgumentException("invalid flight number");
		}
		return normalized;
	}
}
