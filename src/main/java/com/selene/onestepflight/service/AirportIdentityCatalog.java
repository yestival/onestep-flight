package com.selene.onestepflight.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Immutable runtime catalog loaded from the generated classpath resource; it never downloads at startup. */
public final class AirportIdentityCatalog {
	private final Metadata metadata;
	private final Map<String, AirportIdentity> airports;

	public AirportIdentityCatalog(ObjectMapper mapper) {
		this(mapper, resource());
	}

	AirportIdentityCatalog(ObjectMapper mapper, InputStream input) {
		try (InputStream source = input) {
			JsonNode root = mapper.readTree(source);
			JsonNode rawMetadata = root.path("metadata");
			metadata = new Metadata(text(rawMetadata, "datasetVersion"), text(rawMetadata, "fetchedAt"),
					text(rawMetadata, "generatorVersion"), rawMetadata.path("recordCount").asInt());
			LinkedHashMap<String, AirportIdentity> values = new LinkedHashMap<>();
			for (JsonNode node : root.path("airports")) {
				List<String> aliases = node.path("aliases").isArray()
						? java.util.stream.StreamSupport.stream(node.path("aliases").spliterator(), false)
								.filter(JsonNode::isTextual).map(JsonNode::asText).toList()
						: List.of();
				AirportIdentity identity = new AirportIdentity(text(node, "iataCode"), nullable(node, "icaoCode"),
						text(node, "name"), nullable(node, "municipality"), text(node, "isoCountry"),
						nullable(node, "countryName"), node.path("latitude").asDouble(),
						node.path("longitude").asDouble(), text(node, "type"),
						node.path("scheduledService").asBoolean(false), aliases);
				AirportIdentity duplicate = values.putIfAbsent(identity.iataCode(), identity);
				if (duplicate != null) throw new IllegalStateException("Duplicate airport IATA " + identity.iataCode());
			}
			if (metadata.datasetVersion() == null || metadata.datasetVersion().isBlank()
					|| values.size() != metadata.recordCount()) {
				throw new IllegalStateException("airport-identities.json metadata does not match its records");
			}
			airports = Map.copyOf(values);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to load airport-identities.json", exception);
		}
	}

	public Optional<AirportIdentity> find(String iataCode) {
		if (iataCode == null) return Optional.empty();
		return Optional.ofNullable(airports.get(iataCode.trim().toUpperCase(Locale.ROOT)));
	}

	public String datasetVersion() { return metadata.datasetVersion(); }
	public Metadata metadata() { return metadata; }
	public int size() { return airports.size(); }

	private static InputStream resource() {
		InputStream stream = AirportIdentityCatalog.class.getResourceAsStream("/airport-identities.json");
		if (stream == null) throw new IllegalStateException("Missing /airport-identities.json");
		return stream;
	}

	private static String text(JsonNode node, String field) {
		String value = nullable(node, field);
		if (value == null) throw new IllegalStateException("Missing airport identity field " + field);
		return value;
	}

	private static String nullable(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
	}

	public record Metadata(String datasetVersion, String fetchedAt, String generatorVersion, int recordCount) { }
}
