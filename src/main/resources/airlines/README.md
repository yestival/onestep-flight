# Bundled airline registry

This directory contains the runtime-local airline registry snapshot and its
machine-readable provenance. See `openflights-airlines.properties` for the
pinned source revision, integrity values, licensing, generation method, and
known limitations.

`airline-name-aliases.tsv` is a separately maintained OneStep Flight
supplement for exact display-name matching, including common Chinese names and
brand abbreviations. It is project data, not another external snapshot or a
runtime network dependency, and it is intentionally non-exhaustive. Each row
maps an exact display alias to an IATA code and may include an ICAO and canonical
carrier-name discriminator where passenger, cargo, or historical records share
a code; genuinely duplicate mappings remain ambiguous. The pinned OpenFlights
snapshot remains the authoritative carrier registry and supplies carrier records,
status, and duplicate-code handling.
