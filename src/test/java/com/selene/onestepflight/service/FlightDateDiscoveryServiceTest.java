package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.BoardingPassExtractionResponse;
import com.selene.onestepflight.dto.DocumentFlightSegment;
import com.selene.onestepflight.dto.DocumentType;
import com.selene.onestepflight.dto.ExtractedField;
import com.selene.onestepflight.dto.FlightDateDiscoveryRequest;
import com.selene.onestepflight.dto.FlightDateDiscoveryResponse;

class FlightDateDiscoveryServiceTest {

	private final ResolvedFlightService resolvedFlightService =
			new ResolvedFlightService(null, null, null, null);

	@Test
	void passesOnlyTheSelectedSegmentsTrustedHintsAndKeepsAllDocumentSegments() {
		RecordingProvider provider = new RecordingProvider(LocalDate.of(2026, 8, 26), false);
		FlightDateDiscoveryService service = new FlightDateDiscoveryService(provider, resolvedFlightService);
		DocumentFlightSegment first = segment("CA123", "PEK", "SHA", "09:00", 0.95d);
		DocumentFlightSegment selected = segment("BA117", "LHR", "JFK", "18:05", 0.95d);
		BoardingPassExtractionResponse document = document(List.of(first, selected), List.of());

		FlightDateDiscoveryResponse response = service.discover(
				new FlightDateDiscoveryRequest("BA 117", document, 1));

		assertThat(response.departureDate()).isEqualTo(LocalDate.of(2026, 8, 26));
		assertThat(response.dateRequired()).isFalse();
		assertThat(provider.flightNumber).isEqualTo("BA117");
		assertThat(provider.departureHint).isEqualTo("LHR");
		assertThat(provider.arrivalHint).isEqualTo("JFK");
		assertThat(provider.scheduledHint).isEqualTo("18:05");
		assertThat(document.segments()).containsExactly(first, selected);
	}

	@Test
	void dropsLowConfidenceAndReviewedHintsBeforeCallingTheProvider() {
		RecordingProvider provider = new RecordingProvider(LocalDate.of(2026, 8, 26), false);
		FlightDateDiscoveryService service = new FlightDateDiscoveryService(provider, resolvedFlightService);
		DocumentFlightSegment selected = segment("BA117", "LHR", "JFK", "18:05", 0.50d);
		BoardingPassExtractionResponse document = document(List.of(selected),
				List.of("segments[0].arrival_airport", "segments[0].scheduled_departure"));

		FlightDateDiscoveryResponse response = service.discover(
				new FlightDateDiscoveryRequest("BA117", document, 0));

		assertThat(response.dateRequired()).isFalse();
		assertThat(provider.departureHint).isNull();
		assertThat(provider.arrivalHint).isNull();
		assertThat(provider.scheduledHint).isNull();
	}

	@Test
	void safelyRequiresTheDateForWrongSegmentsAmbiguityAndProviderFailures() {
		DocumentFlightSegment selected = segment("BA117", "LHR", "JFK", "18:05", 0.95d);
		BoardingPassExtractionResponse document = document(List.of(selected), List.of());
		RecordingProvider ambiguousProvider = new RecordingProvider(null, false);
		FlightDateDiscoveryService ambiguousService =
				new FlightDateDiscoveryService(ambiguousProvider, resolvedFlightService);

		assertThat(ambiguousService.discover(new FlightDateDiscoveryRequest("BA117", document, 0)))
				.isEqualTo(FlightDateDiscoveryResponse.required());
		assertThat(ambiguousService.discover(new FlightDateDiscoveryRequest("BA118", document, 0)))
				.isEqualTo(FlightDateDiscoveryResponse.required());
		assertThat(ambiguousService.discover(new FlightDateDiscoveryRequest("BA117", document, 4)))
				.isEqualTo(FlightDateDiscoveryResponse.required());

		FlightDateDiscoveryService failingService = new FlightDateDiscoveryService(
				new RecordingProvider(null, true), resolvedFlightService);
		assertThat(failingService.discover(new FlightDateDiscoveryRequest("BA117", document, 0)))
				.isEqualTo(FlightDateDiscoveryResponse.required());
	}

	private BoardingPassExtractionResponse document(List<DocumentFlightSegment> segments,
			List<String> needsReview) {
		return new BoardingPassExtractionResponse(DocumentType.ITINERARY, null, null,
				null, null, null, null, null, null, null, null,
				false, false, Map.of(), segments, needsReview,
				"NUTRIENT_DWS_DATA_EXTRACTION", Instant.EPOCH);
	}

	private DocumentFlightSegment segment(String flightNumber, String departureAirport,
			String arrivalAirport, String scheduledDeparture, double hintConfidence) {
		Map<String, ExtractedField> fields = Map.of(
				"flight_number", field(flightNumber, 0.95d),
				"departure_airport", field(departureAirport, hintConfidence),
				"arrival_airport", field(arrivalAirport, hintConfidence),
				"scheduled_departure", field(scheduledDeparture, hintConfidence));
		return new DocumentFlightSegment(flightNumber, null, departureAirport, arrivalAirport,
				scheduledDeparture, null, null, null, null, null,
				0.95d, 0, null, fields);
	}

	private ExtractedField field(String value, double confidence) {
		return new ExtractedField(value, confidence, 0, null, "stub");
	}

	private static final class RecordingProvider implements FlightDateDiscoveryProvider {
		private final LocalDate result;
		private final boolean fail;
		private String flightNumber;
		private String departureHint;
		private String arrivalHint;
		private String scheduledHint;

		private RecordingProvider(LocalDate result, boolean fail) {
			this.result = result;
			this.fail = fail;
		}

		@Override
		public LocalDate discoverDepartureDate(String flightNumber, String departureAirportHint,
				String arrivalAirportHint, String scheduledDepartureHint) {
			if (fail) throw new ExternalServiceUnavailableException("STUB_UNAVAILABLE");
			this.flightNumber = flightNumber;
			this.departureHint = departureAirportHint;
			this.arrivalHint = arrivalAirportHint;
			this.scheduledHint = scheduledDepartureHint;
			return result;
		}
	}
}
