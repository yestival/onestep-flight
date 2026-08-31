# OneStep Flight / 一步乘机

OneStep Flight is an accessible flight companion that combines document evidence, multiple operational flight sources, airport-local time, and journey progress to show one clear next action at a time.

Built for the DevNetwork **[API + Cloud + AI] Hackathon 2026**, including the Nutrient DWS Challenge and SerpApi Best AI Use Case tracks.

> **Where Nutrient is critical:** Nutrient DWS turns an uploaded boarding pass or ticket into structured, cited evidence that OneStep can verify against operational sources, so a traveler does not have to interpret a dense travel document alone.

## Run locally

Requirements: Java 17 and the included Maven Wrapper.

Configure these environment variables before starting VS Code or the terminal. Never put their values in Java, JavaScript, properties, screenshots, logs, URLs, or Git history.

- `NUTRIENT_API_KEY`
- `SERPAPI_API_KEY`
- `AERODATABOX_RAPIDAPI_KEY`
- `VARIFLIGHT_API_KEY`

Windows PowerShell:

```powershell
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '18080'
.\mvnw.cmd spring-boot:run
```

Open <http://127.0.0.1:18080/>. A safe readiness endpoint is available at <http://127.0.0.1:18080/api/integrations/status>; it returns only `READY`, `MISSING`, or `DISABLED`, never secret values. VariFlight is deliberately `DISABLED` unless `VARIFLIGHT_ENABLED=true` is explicitly set, even when an old key remains on the computer.

Local development uses an in-memory H2 database and binds the application only to `127.0.0.1:18080`. Share sessions survive page reloads while that Spring Boot process is running, but all local share links stop working when the process restarts. The H2 console is a local-development aid only and is disabled by the production profile.

## Deploy on Render

The root `render.yaml` is a Render Blueprint for a free Docker web service and a free PostgreSQL database in Frankfurt. The service builds with Java 17 in the multi-stage `Dockerfile`, runs as a non-root user, listens on Render's `PORT`, and uses `/api/health` for health checks.

1. Publish the repository, then create a new **Blueprint** in Render and select this repository. Render reads `render.yaml`; no manual database URL assembly is needed.
2. During the first Blueprint creation, enter only these three secret values when Render prompts for variables marked `sync: false`: `NUTRIENT_API_KEY`, `SERPAPI_API_KEY`, and `AERODATABOX_RAPIDAPI_KEY`.
3. Do not put secret values into `render.yaml`, source files, screenshots, build arguments, or logs. `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, and `DB_PASSWORD` are populated directly from the managed database resource.
4. Wait for `GET /api/health` to return a successful response, then open the generated `https://<service-name>.onrender.com/` URL.

The Blueprint deliberately does not request or provision a VariFlight key and sets `VARIFLIGHT_ENABLED=false`. The other configured live providers continue to support partial success when VariFlight is unavailable.

With the public HTTPS URL, a family link such as `https://<service-name>.onrender.com/share/{viewToken}` can be opened on another device. The public URL contains only the view token; the independent update token stays in the traveler's browser and is sent only in the `X-Share-Update-Token` header. Share sessions expire after 48 hours, and an hourly server job removes expired records.

| Environment | Database | Share-link persistence |
|---|---|---|
| Default local profile | In-memory H2 | Lost whenever the local Spring Boot process restarts |
| `prod` profile | PostgreSQL from `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, and `DB_PASSWORD` | Survives web-service restarts and deploys while the database remains available |

Render's free web service can spin down after 15 minutes without inbound traffic and may take about a minute to wake. Free Render PostgreSQL is intended for demos rather than durable production: it is limited to 1 GB, expires 30 days after creation, and has no backups. Upgrade or migrate the database before that deadline if shared journeys must remain available. See Render's official [Blueprint specification](https://render.com/docs/blueprint-spec) and [free-instance limits](https://render.com/docs/free).

## Real external integrations

| Integration | Purpose | Reality and safety boundary |
|---|---|---|
| Nutrient Data Extraction API | Extract fields and citations from PDF/JPG/PNG travel documents | Real external API when configured; no fixture fallback |
| SerpApi Flight Result | Flight-result status, route, gate/terminal and operational times when returned | Real external API when configured |
| SerpApi Google Search / AI Mode / Maps | Find a verified airport map and the current airport's main general-passenger contact | Map discovery and multilingual phone lookup remain separate; only verified airport/operator sources or an exact Google Maps airport-location fallback are accepted |
| AeroDataBox via RapidAPI | Flight-number/date lookup, quality, departure times, terminal, gate and `checkInDesk` | Real external API when configured |
| VariFlight Tripmatch MCP | `searchFlightsByNumber` using JSON-RPC/MCP over JSON or SSE | Real external API when configured |

AeroDataBox `departure.baggageBelt` is deliberately ignored because it describes the **arrival reclaim carousel**, not a departure bag-drop desk. `checkInDesk` is treated only as a check-in area; when a dedicated bag-drop desk is unknown, OneStep may direct the traveler there to ask staff where bag drop is. OneStep labels a destination as a dedicated bag-drop desk only when a provider explicitly returns a dedicated bag-drop field.

SerpApi real-time flight data keeps operational flight state synchronized. Airport resources are resolved independently and strictly on demand: confirming a flight or showing a journey step performs no airport-resource lookup; the first **View airport map** click requests only the map, and the first **Contact airport** click requests only the contact. **View airport map** searches verified official domains first, uses a map-only AI task only when needed, and executes the AI-proposed local-language map query when the proposed URL is unusable; true interactive maps, map documents, and official map landing pages outrank guide pages, with an exact Google Maps airport location as a labelled fallback. **Contact airport** uses a separate multilingual AI task for one general passenger-information, customer-service, contact-centre, or airport-switchboard number with a directly bound official citation, then executes its official-domain local-language query when the dedicated field has no verified phone. If no safe number is public, OneStep offers a verified official contact page. Both features reuse the existing `SERPAPI_API_KEY`.

## Multi-source merge and provenance

The live lookup runs in two stages: AeroDataBox first establishes any available route hint, then SerpApi and VariFlight use that hint for stricter matching. Each adapter has a five-minute cache; a forced refresh bypasses it. The merge does not choose by provider name alone. For each field it retains:

- the selected value and provider;
- the provider's source-update time, when supplied;
- OneStep's retrieval time;
- the provider quality/level;
- every candidate value and whether the sources conflict.

Selection considers explicit field presence, quality, source-update time, and retrieval time. Route identity is checked using normalized flight number, departure date, and known route hints. A multi-record response is never accepted by simply taking its first item.

Partial failure is supported: if one provider succeeds, its verifiable fields may still drive the journey while the UI shows the other providers' states. If all three fail, the API returns `ALL_LIVE_FLIGHT_PROVIDERS_FAILED`; it never substitutes fixture data. During a refresh failure, the browser preserves the last successful snapshot and current action.

Provider failure outcomes distinguish `NOT_CONFIGURED`, `AUTHENTICATION_FAILED`, `FORBIDDEN_OR_QUOTA`, `NOT_FOUND`, `RATE_LIMITED`, `TIMEOUT`, `UPSTREAM_ERROR`, and `PARSE_ERROR`.

## Time and next-action model

The backend computes the operational time context from server time and the departure timestamp's UTC offset. Scheduled, estimated/revised, actual, boarding, effective departure, source-update, and retrieval times remain separate. A missing provider update time remains missing; it is not replaced with the current time.

Deterministic rules return one of `CHECK_IN`, `BAG_DROP`, `SECURITY`, `GO_TO_GATE`, `WAIT`, `BOARD_NOW`, `IN_FLIGHT`, `BAGGAGE_CLAIM`, `EXIT_AIRPORT`, or `ASK_FOR_HELP`.

Completed journey state and the latest flight snapshot are sent to the backend for every decision. Normal step progression rechecks all provider adapters within their cache rules; explicit refresh bypasses backend caches and can run again as soon as the current request finishes. Cancellation, departure, arrival, stale near-departure data, missing gate near departure, and overdue boarding produce stop/help behavior rather than continuing the old plan.

## API overview

- `GET /api/integrations/status` — readiness only, no secret values.
- `POST /api/documents/boarding-pass` — Nutrient extraction and field evidence.
- `GET /api/flights/{flightNumber}?departureDate=YYYY-MM-DD&demo=false` — original SerpApi adapter endpoint.
- `POST /api/assistance/airport-resources` — independent verified map and general-passenger contact resources for the current airport context.
- `POST /api/journeys/resolve-flight` — merge document evidence with all configured live providers.
- `POST /api/journeys/next-action` — re-resolve within cache rules and calculate one next action.
- `POST /api/journeys/refresh-flight` — force a new provider query for the confirmation snapshot.
- `POST /api/share-sessions` — create a privacy-minimized, read-only family view and return independent view/update tokens.
- `GET /api/share-sessions/{viewToken}` — read the current shared journey state without login.
- `PUT /api/share-sessions/{viewToken}` — update it with the private `X-Share-Update-Token` header.
- `DELETE /api/share-sessions/{viewToken}` — stop sharing with the private `X-Share-Update-Token` header.

Airport resource lookup first resolves IATA against the generated local OurAirports identity catalog (canonical name, municipality, country and coordinates). Google Search and Google Maps retain typed candidates that are reconciled against that canonical entity; an IATA substring in a hostname or an “official website” snippet is never sufficient. Google AI Mode is limited to strict-JSON discovery on already verified airport/operator domains. Libphonenumber normalizes ordinary numbers in the airport’s country, preserves compatible national short codes only from verified passenger pages, and keeps phone/page provenance atomic. Map discovery independently ranks official interactive maps, entry pages, PDFs, official external-link chains and an exact Google Maps airport-place fallback. Results remain safely unavailable when the resource-specific evidence threshold is not met.

The resolved journey response exposes `providerResults`, `fieldProvenance`, `conflicts`, `retrievedAt`, `freshestSourceUpdatedAt`, `stale`, `live`, and `demo` alongside normalized flight, route, times, terminal, gate, check-in area, any explicitly known dedicated bag-drop desk, and the independently modelled arrival baggage belt when published.

For local testing, a generated family URL starts with `http://127.0.0.1:18080`; it opens only on the same computer running OneStep Flight. Use an HTTPS deployment (or an intentionally configured reachable host) before sending a link to another device. The view URL never contains the update token, and the update token stays only in the traveler's browser session.

The read-only family page is served at `/share/{viewToken}`. It needs no login, lets the viewer choose 中文/English/Español, and polls the safe snapshot every 13 seconds so meaningful updates can render within 15 seconds while the share remains active.

## Browser walkthrough

1. Open the home page and choose **Upload booking confirmation, e-ticket, or boarding pass**.
2. Select one file from `test-fixtures/documents/`. For a live manual lookup, enter a currently operating flight number and its current departure date; do not reuse a dated example as evidence of a current flight.
3. Confirm Nutrient-extracted fields one at a time. The confirmation page then merges the document with configured live providers.
4. Check provider chips, terminal, gate, check-in area, airport-local time, countdown, freshness, conflicts, and expandable evidence.
5. Confirm the flight. Answer whether checked baggage is present and complete one action at a time.
6. Use **返回上一步 / Back one step** at the bottom of the journey; user-entered progress is restored while newer live updates remain intact.
7. Use **朗读当前步骤 / Read this step aloud** to hear the action, destination, terminal, gate, counter/zone, and time.
8. At the bottom of a normal journey step, use **查看机场地图 / View airport map** or **联系机场 / Contact airport**. The high-contrast help card keeps only **联系机场 / Contact airport** at the upper left and the close button at the upper right; it never shows a map entry. Calling still requires a second explicit tap on the displayed number.
9. Open the family-sharing dialog to create a live link. The dialog contains exactly two native share actions: **分享链接 / Share link** and **分享图片 / Share image**. Meaningful journey changes update the same link; ending, clearing, or replacing the journey revokes the server share session, with an offline-safe retry if revocation is temporarily unavailable.
10. Switch 中文/English/Español, reload to verify session recovery, and repeat at 360×800, 390×844, and 200% zoom.

For local airport-resource checks, `?qaAirport=1` shows the **Airport information lookup** card only on `localhost`, `127.0.0.1`, or `::1` while the flight-confirmation screen is active. Enter an IATA code and optional terminal; the single query action refreshes map and contact results together and presents only passenger-facing actions. Technical diagnostics remain in the live-test report and application logs, not in the query result UI.

## Safe fixture scenarios

Append `?demoScenarios=true` to the home URL to reveal `NORMAL`, `GATE_CHANGE`, `DELAY`, `CANCEL`, and `STALE`. Every scenario response has `demo=true`, uses a simulated source label, and is never presented as live. The stable BA117 fixture used by **Use BA 117 demo flight** is also explicitly labelled.

For a same-day state-machine check, open <http://127.0.0.1:18080/?qaJourney=true>. This hidden local QA route uses a dynamically calculated departure date and the explicitly labelled `BA117` demo `NORMAL` scenario, then lets a tester advance through check-in, bag drop, security, gate, onboard, baggage claim, and exit without waiting for a real flight clock. It is test-only, does not claim a live provider result, and does not appear in the ordinary home flow.

Keep this README synchronized with the implemented UI and verified test evidence before using it as video narration or promotional copy.

## Current prototype limitations

- Only official airport/operator map and passenger-contact resources returned and verified from the current lookup are used; walking time, security queues, airline eligibility rules, indoor navigation, and a globally authoritative bag-drop desk feed are not available.
- Browser speech quality depends on installed operating-system voices.
- Family sharing is deliberately account-free and stores only a short, privacy-minimized journey snapshot. The default local profile uses in-memory H2 and loses links on restart; the `prod` profile uses PostgreSQL and preserves them until their 48-hour expiry while that database remains available. There is no caregiver account or messaging service.
- Provider plans, quotas, historical coverage, and field availability vary. Missing data is shown as missing and should be confirmed with airport staff and airport displays.

## Privacy and verification

### Synthetic document-classification fixtures

The files in `test-fixtures/documents/` are generated test artifacts, not travel documents. Every page or image is prominently marked **SYNTHETIC TEST DOCUMENT — NOT VALID FOR TRAVEL** and contains no passenger name, PNR, booking reference, ticket number, passport data, payment data, or barcode. Regenerate the same privacy-safe artifacts with `scripts/generate-synthetic-documents.ps1` when needed.

| Fixture | Expected `document_type` | Expected journey behavior |
|---|---|---|
| `booking-confirmation.pdf` | `BOOKING_CONFIRMATION` | Does not imply check-in; begin with `CHECK_IN` after the flight identity is confirmed. |
| `e-ticket-itinerary.pdf` | `E_TICKET` or, if the provider identifies the layout more generally, `ITINERARY` | Does not imply check-in; begin with `CHECK_IN`. |
| `boarding-pass.pdf` | `BOARDING_PASS` | May skip `CHECK_IN` only when the classification confidence is at least `0.85`; otherwise ask for confirmation/manual input. |
| `booking-page-screenshot.png` | `BOOKING_CONFIRMATION` | Does not imply check-in; begin with `CHECK_IN`. |
| `non-flight-document.png` | `UNKNOWN` | Require confirmation/manual flight number and date; never infer check-in. |

The extraction schema accepts only `BOARDING_PASS`, `ITINERARY`, `E_TICKET`, `BOOKING_CONFIRMATION`, or `UNKNOWN`. Unknown provider labels are normalized to `UNKNOWN`. It requires a `document_type` and a `segments` array; a non-flight file returns `UNKNOWN` with an empty array instead of inventing a flight number or date. Name, PNR, booking-reference, ticket-number, passport, and barcode fields are not requested or exposed by the public extraction DTO.

Document extraction returns explicit `segments[]` rather than silently selecting an itinerary leg. A document with multiple flight segments requires the traveler to choose the intended segment; a document with one segment still requires confirmation before the live lookup. Only a high-confidence `BOARDING_PASS` classification (at least `0.85`) can imply that check-in is complete and skip `CHECK_IN`. An itinerary, e-ticket, booking confirmation, `UNKNOWN`, or lower-confidence boarding-pass classification never skips check-in automatically.

BA117 is a real British Airways LHR → JFK flight. The repository's PDF and screenshot fixtures—including the older `output/pdf/ba117-live-privacy-safe-test.pdf` artifact—are privacy-safe synthetic test materials, not valid travel credentials. Values printed on a fixture never fill a live provider field unless the UI explicitly labels them as document evidence.

No additional real traveler document was supplied for this workspace run. The five files in `test-fixtures/documents/` are the complete document test set used here, and all five are synthetic.

Before a submission build:

```powershell
node --check src/main/resources/static/app.js
.\mvnw.cmd test
.\mvnw.cmd package
```

Also inspect `git diff --check`, test the full browser walkthrough, confirm that live responses have `demo=false`, and search the repository for accidental credentials before publishing.
