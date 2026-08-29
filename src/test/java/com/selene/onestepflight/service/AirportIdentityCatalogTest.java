package com.selene.onestepflight.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class AirportIdentityCatalogTest {
	@Test
	void loadsGeneratedOurAirportsMetadataAndResolvesCodeOnlyIdentity() {
		AirportIdentityCatalog catalog = new AirportIdentityCatalog(new ObjectMapper());

		assertThat(catalog.size()).isGreaterThan(8_000);
		assertThat(catalog.datasetVersion()).matches("[0-9a-f]{16}");
		AirportIdentity identity = catalog.find("ams").orElseThrow();
		assertThat(identity.name()).isEqualTo("Amsterdam Airport Schiphol");
		assertThat(identity.municipality()).isEqualTo("Amsterdam");
		assertThat(identity.countryName()).isEqualTo("Netherlands");
	}
}
