package com.selene.onestepflight.service.airline;

import java.time.Instant;

public record AirlineRegistryMetadata(
		String formatVersion,
		String sourceName,
		String sourcePage,
		String sourceRepository,
		String sourceRevision,
		Instant sourceRevisionAt,
		String sourceFile,
		String sourceUrl,
		String databaseLicense,
		String databaseLicenseUrl,
		String contentsLicense,
		String contentsLicenseUrl,
		String attribution,
		String originalSha256,
		int recordCount,
		Instant generatedAt,
		String generatedBy,
		String sourceSchema,
		String statusSemantics,
		String coverage,
		String knownLimitations,
		String additionalRightsCaveat) {
}
