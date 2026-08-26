package com.selene.onestepflight.service.airline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class AirlineRegistryTest {

	private final AirlineRegistry registry = AirlineRegistry.loadDefault();

	@Test
	void loadsEveryPinnedSourceRowAndExposesCompleteProvenance() {
		AirlineRegistryMetadata metadata = registry.metadata();

		assertThat(registry.records()).hasSize(6162);
		assertThat(registry.records()).hasSize(metadata.recordCount());
		assertThat(metadata.sourceName()).isEqualTo("OpenFlights Airline Database");
		assertThat(metadata.sourceRevision()).isEqualTo("5d623a6969a1adee7961cf1c9a8a212c4a784713");
		assertThat(metadata.sourceRevisionAt()).isEqualTo(Instant.parse("2017-02-02T11:32:12Z"));
		assertThat(metadata.databaseLicense()).contains("ODbL");
		assertThat(metadata.contentsLicense()).contains("DbCL");
		assertThat(metadata.originalSha256()).hasSize(64)
				.isEqualTo("39be1a432e8b04ebc12860c29281c974a9cb52169c82b2456a835d66ab1548a1");
		assertThat(metadata.generatedAt()).isNotNull();
		assertThat(metadata.generatedBy()).isEqualTo("scripts/import-openflights-airlines.ps1");
		assertThat(metadata.knownLimitations()).contains("active flag is not reliable", "no validity periods");
		assertThat(metadata.additionalRightsCaveat()).contains("GNU Free Documentation License");
	}

	@Test
	void resolvesUnrelatedAirlinesAcrossRegionsAndNumericIataCodes() {
		assertHighIata("AA", "American Airlines", "United States");
		assertHighIata("NH", "All Nippon Airways", "Japan");
		assertHighIata("G9", "Air Arabia", "United Arab Emirates");
		assertHighIata("SA", "South African Airways", "South Africa");
		assertHighIata("QF", "Qantas", "Australia");

		AirlineResolution numericLeading = registry.lookupIata("1T");
		assertThat(numericLeading.isHighConfidence()).isTrue();
		assertThat(numericLeading.uniqueCandidate()).get()
				.extracting(AirlineRecord::name).isEqualTo("1Time Airline");
	}

	@Test
	void searchesIcaoNameCallsignAliasAndAccentFoldedName() {
		assertThat(registry.lookupIcao("aal").uniqueCandidate()).get()
				.extracting(AirlineRecord::name).isEqualTo("American Airlines");
		assertThat(registry.searchIdentity("AMERICAN").uniqueCandidate()).get()
				.extracting(AirlineRecord::callsign).isEqualTo("AMERICAN");
		assertThat(registry.searchIdentity("ANA All Nippon Airways").uniqueCandidate()).get()
				.extracting(AirlineRecord::iata).isEqualTo("NH");
		assertThat(registry.searchIdentity("Aeromexico").uniqueCandidate()).get()
				.extracting(AirlineRecord::iata).isEqualTo("AM");
	}

	@Test
	void resolvesCompleteIataAndIcaoFlightDesignatorsWithoutNetwork() {
		AirlineResolution iata = registry.resolveFlightPrefix("Ｇ９ – 123");
		AirlineResolution icao = registry.resolveFlightPrefix("AAL 123");

		assertThat(iata.normalizedQuery()).isEqualTo("G9");
		assertThat(iata.matchType()).isEqualTo(AirlineMatchType.IATA);
		assertThat(iata.uniqueCandidate()).get().extracting(AirlineRecord::name).isEqualTo("Air Arabia");
		assertThat(icao.normalizedQuery()).isEqualTo("AAL");
		assertThat(icao.matchType()).isEqualTo(AirlineMatchType.ICAO);
		assertThat(icao.uniqueCandidate()).get().extracting(AirlineRecord::name).isEqualTo("American Airlines");
	}

	@Test
	void preservesCodeReuseAndDoesNotSilentlyResolveCurrentAmbiguity() {
		AirlineResolution ambiguous = registry.lookupIata("1I");

		assertThat(ambiguous.candidates()).hasSize(7);
		assertThat(ambiguous.candidates())
				.filteredOn(candidate -> candidate.status() == AirlineStatus.ACTIVE)
				.hasSize(2);
		assertThat(ambiguous.ambiguous()).isTrue();
		assertThat(ambiguous.confidence()).isEqualTo(AirlineResolutionConfidence.LOW);
		assertThat(ambiguous.uniqueCandidate()).isEmpty();
	}

	@Test
	void keepsInactiveReuseCandidatesWhilePreferringTheOnlyActiveCandidate() {
		AirlineResolution reused = registry.lookupIata("IJ");

		assertThat(reused.candidates()).hasSize(6);
		assertThat(reused.candidates())
				.filteredOn(candidate -> candidate.status() == AirlineStatus.ACTIVE)
				.singleElement()
				.extracting(AirlineRecord::name).isEqualTo("Spring Airlines Japan");
		assertThat(reused.isHighConfidence()).isTrue();
		assertThat(reused.ambiguous()).isFalse();
		assertThat(reused.uniqueCandidate()).get()
				.extracting(AirlineRecord::status).isEqualTo(AirlineStatus.ACTIVE);
	}

	@Test
	void preservesSourceStatusAndNeverInventsValidityDates() {
		assertThat(registry.records())
				.anySatisfy(record -> {
					assertThat(record.sourceStatus()).isEqualTo("n");
					assertThat(record.status()).isEqualTo(AirlineStatus.INACTIVE);
				})
				.allSatisfy(record -> {
					assertThat(record.validFrom()).isNull();
					assertThat(record.validTo()).isNull();
				});
		assertThat(registry.records()).anyMatch(record -> record.status() == AirlineStatus.ACTIVE);
		assertThat(registry.records()).anyMatch(record -> record.status() == AirlineStatus.INACTIVE);
	}

	@Test
	void resourceContractCanPreserveExplicitHistoricalAndUnknownStatuses() {
		String csv = "90001,Historical Example,Legacy Example,H1,HST,OLD TIMER,Exampleland,H\n"
				+ "90002,Unknown Example,\\N,U1,UNK,\\N,Exampleland,?\n";
		byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
		AirlineRegistry custom = AirlineRegistry.fromSnapshot(bytes, metadataFor(bytes, 2));

		assertThat(custom.records()).extracting(AirlineRecord::status)
				.containsExactly(AirlineStatus.HISTORICAL, AirlineStatus.UNKNOWN);
		assertThat(custom.lookupIata("H1").confidence()).isEqualTo(AirlineResolutionConfidence.MEDIUM);
	}

	@Test
	void checksumAndRecordCountProtectDeterministicImports() {
		byte[] bytes = "1,Example,\\N,E1,EXP,EXAMPLE,Exampleland,Y\n"
				.getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> AirlineRegistry.fromSnapshot(bytes, metadataFor(bytes, 2)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("record count mismatch");
		AirlineRegistryMetadata wrongHash = metadataFor(bytes, 1);
		wrongHash = new AirlineRegistryMetadata(wrongHash.formatVersion(), wrongHash.sourceName(),
				wrongHash.sourcePage(), wrongHash.sourceRepository(), wrongHash.sourceRevision(),
				wrongHash.sourceRevisionAt(), wrongHash.sourceFile(), wrongHash.sourceUrl(),
				wrongHash.databaseLicense(), wrongHash.databaseLicenseUrl(), wrongHash.contentsLicense(),
				wrongHash.contentsLicenseUrl(), wrongHash.attribution(), "0".repeat(64), 1,
				wrongHash.generatedAt(), wrongHash.generatedBy(), wrongHash.sourceSchema(),
				wrongHash.statusSemantics(), wrongHash.coverage(), wrongHash.knownLimitations(),
				wrongHash.additionalRightsCaveat());
		AirlineRegistryMetadata finalWrongHash = wrongHash;
		assertThatThrownBy(() -> AirlineRegistry.fromSnapshot(bytes, finalWrongHash))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("checksum mismatch");
	}

	private void assertHighIata(String code, String name, String country) {
		AirlineResolution resolution = registry.lookupIata(code);
		assertThat(resolution.isHighConfidence()).isTrue();
		assertThat(resolution.uniqueCandidate()).get().satisfies(candidate -> {
			assertThat(candidate.name()).isEqualTo(name);
			assertThat(candidate.country()).isEqualTo(country);
		});
	}

	private AirlineRegistryMetadata metadataFor(byte[] bytes, int count) {
		return new AirlineRegistryMetadata("test", "test", "https://example.test/source",
				"https://example.test/repository", "revision", Instant.EPOCH,
				"airlines.dat", "https://example.test/airlines.dat", "ODbL-1.0",
				"https://opendatacommons.org/licenses/odbl/1-0/", "DbCL-1.0",
				"https://opendatacommons.org/licenses/dbcl/1-0/", "test attribution",
				AirlineRegistry.sha256(bytes), count, Instant.EPOCH, "test", "eight columns",
				"test", "test", "test", "test");
	}
}
