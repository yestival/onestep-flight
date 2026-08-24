package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.selene.onestepflight.dto.FlightResponse;

class ResolvedFlightServiceFieldSemanticsTest {

	private final ResolvedFlightService service = new ResolvedFlightService(null, null, null, null);

	@Test
	void doesNotCreateDocumentChangesForEquivalentTerminalAndGateFormats() {
		FlightResponse flight = flight("Terminal 2", "Gate B42");

		assertThat(service.findDocumentChanges("T2", "B 42", flight, Instant.EPOCH)).isEmpty();
	}

	@Test
	void preservesRealTerminalAndGateChanges() {
		FlightResponse flight = flight("Terminal 3", "Gate B43");

		assertThat(service.findDocumentChanges("T2", "B42", flight, Instant.EPOCH))
				.extracting(change -> change.type())
				.containsExactly("TERMINAL_CHANGED", "GATE_CHANGED");
	}

	private FlightResponse flight(String terminal, String gate) {
		OffsetDateTime departure = OffsetDateTime.parse("2026-09-04T16:10Z");
		return new FlightResponse("BA117", LocalDate.of(2026, 9, 4),
				"CDG", "Paris Charles de Gaulle", "XMN", "Xiamen Gaoqi",
				departure, departure, null, departure.plusHours(11), null, null, null,
				terminal, gate, "D", "EXPECTED", 0, 0, Instant.EPOCH, Instant.EPOCH,
				"TEST", null, null, true, false, false);
	}
}
