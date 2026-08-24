package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class FlightFieldSemanticsTest {

	@Test
	void recognizesEquivalentTerminalFormatsButKeepsDifferentTerminalsDistinct() {
		List<String> terminalTwo = List.of("T2", "T 2", "Terminal 2", "航站楼 2", "2");
		terminalTwo.forEach(value -> assertThat(FlightFieldSemantics.equivalent("terminal", "T2", value))
				.as("T2 should equal %s", value).isTrue());
		assertThat(FlightFieldSemantics.equivalent("terminal", "T2", "Terminal 3")).isFalse();
	}

	@Test
	void recognizesEquivalentGateAndFlightNumberFormats() {
		assertThat(FlightFieldSemantics.equivalent("gate", "B42", "Gate B 42")).isTrue();
		assertThat(FlightFieldSemantics.equivalent("gate", "B-42", "B 42")).isTrue();
		assertThat(FlightFieldSemantics.equivalent("gate", "B42", "B43")).isFalse();
		assertThat(FlightFieldSemantics.equivalent("flightNumber", "BA117", "BA 117")).isTrue();
		assertThat(FlightFieldSemantics.equivalent("flightNumber", "BA-117", "BA117")).isTrue();
		assertThat(FlightFieldSemantics.equivalent("flightNumber", "BA117", "BA118")).isFalse();
	}

	@Test
	void comparesOffsetTimesByInstant() {
		OffsetDateTime utc = OffsetDateTime.parse("2026-09-04T16:10Z");
		OffsetDateTime china = OffsetDateTime.parse("2026-09-05T00:10+08:00");
		assertThat(FlightFieldSemantics.equivalent("scheduledDeparture", utc, china)).isTrue();
		assertThat(FlightFieldSemantics.equivalent("boardingTime", utc, china.plusMinutes(15))).isFalse();
	}

	@Test
	void comparesAirportIdentityByIataInsteadOfNames() {
		assertThat(FlightFieldSemantics.equivalent("departureAirport", "CDG",
				"Aéroport de Paris-Charles de Gaulle (CDG)")).isTrue();
		assertThat(FlightFieldSemantics.equivalent("arrivalAirport", "XMN",
				"Xiamen Gaoqi International Airport (XMN)")).isTrue();
		assertThat(FlightFieldSemantics.equivalent("arrivalAirport", "XMN", "JFK")).isFalse();
		assertThat(FlightFieldSemantics.equivalent("arrivalAirport",
				"Xiamen Gaoqi", "Xiamen Gaoqi International Airport")).isTrue();
	}

	@Test
	void treatsCheckInAreaAsCompatibleWithAMoreSpecificCounterRange() {
		List<String> areaD = List.of("D", "Area D", "Zone D", "Check-in D");
		areaD.forEach(value -> assertThat(FlightFieldSemantics.equivalent("checkInDeskOrZone", "D", value))
				.as("D should equal %s", value).isTrue());
		assertThat(FlightFieldSemantics.equivalent("checkInDeskOrZone", "D",
				"Area D, counters 20–24")).isTrue();
		assertThat(FlightFieldSemantics.specificity("checkInDeskOrZone", "Area D, counters 20–24"))
				.isGreaterThan(FlightFieldSemantics.specificity("checkInDeskOrZone", "D"));
		assertThat(FlightFieldSemantics.equivalent("checkInDeskOrZone",
				"D counters 20-24", "Area D counters 30-34")).isFalse();
		assertThat(FlightFieldSemantics.equivalent("checkInDeskOrZone", "Area D", "Zone E")).isFalse();
	}

	@Test
	void ignoresFormattingAndMissingValuesWithoutHidingCriticalStatusDifferences() {
		assertThat(FlightFieldSemantics.equivalent("codeshare", "Code-Share", " code share ")).isTrue();
		assertThat(FlightFieldSemantics.equivalent("terminal", null, "T2")).isTrue();
		assertThat(FlightFieldSemantics.equivalent("gate", "", "B42")).isTrue();
		assertThat(FlightFieldSemantics.equivalent("status", "EXPECTED", "ON_TIME")).isTrue();
		assertThat(FlightFieldSemantics.equivalent("status", "CANCELLED", "ON_TIME")).isFalse();
	}

	@Test
	void detectsContradictionsAcrossAllNonEmptyCandidates() {
		assertThat(FlightFieldSemantics.hasConflict("checkInDeskOrZone",
				List.of("D", "D counters 20-24", "Area D counters 30-34"))).isTrue();
		assertThat(FlightFieldSemantics.hasConflict("terminal",
				List.of("T2", "Terminal 2", "航站楼 2"))).isFalse();
	}
}
