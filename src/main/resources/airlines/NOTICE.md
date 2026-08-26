# OpenFlights airline database notice

`openflights-airlines.dat` is an unfiltered copy of all 6,162 rows in the
OpenFlights `data/airlines.dat` file at commit
`5d623a6969a1adee7961cf1c9a8a212c4a784713` (2017-02-02).

Contains information from the [OpenFlights Airline Database](https://openflights.org/data.php),
which OpenFlights makes available under the [Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
OpenFlights licenses individual database contents under the
[Database Contents License (DbCL) 1.0](https://opendatacommons.org/licenses/dbcl/1-0/).
OpenFlights also states that airline data derived from Wikipedia may be subject
to the GNU Free Documentation License.

The bundled file is retained as a separately identified database resource. Its
ODbL/DbCL terms and attribution are not replaced by the license, if any, of the
surrounding application code.

The source documents these fields: OpenFlights ID, name, alias, IATA, ICAO,
callsign, country, and active (`Y` or `N`). OpenFlights warns that the active
flag is not reliable, the GitHub data files are only occasionally updated, and
IATA codes for defunct airlines are generally incomplete. Consequently:

- `N` is represented as `INACTIVE`, not invented historical validity evidence.
- no validity dates are inferred;
- duplicate and reused codes remain separate candidates;
- this snapshot is useful supporting evidence, not a current authoritative IATA
  or ICAO registry and not suitable for navigation.

The original file SHA-256 is
`39be1a432e8b04ebc12860c29281c974a9cb52169c82b2456a835d66ab1548a1`.
The deterministic import command is documented in
`scripts/import-openflights-airlines.ps1`.
