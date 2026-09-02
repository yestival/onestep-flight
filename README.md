# OneStep Flight / 一步乘机

OneStep Flight is a mobile-first, accessible flight companion. It combines travel-document evidence, live flight data, airport-local time, and journey progress to present one clear next step.

Built for the DevNetwork **[API + Cloud + AI] Hackathon 2026**, including the Nutrient DWS Challenge and SerpApi Best AI Use Case tracks.

## Highlights

- **One step at a time:** large text keeps the current action clear on a small screen.
- **Three languages with automatic speech:** 中文, English, and Español are supported across the main journey and family view. New screens, choices, and flight changes are read aloud.
- **Travel-document recognition:** Nutrient extracts booking confirmations, e-tickets, itineraries, boarding passes, and screenshots. Travelers can review and complete the result.
- **Multi-segment journeys:** every detected flight leg stays separate. The traveler chooses the current leg before live lookup.
- **Live data comparison:** SerpApi, AeroDataBox, and VariFlight are compared field by field. Gate and terminal changes are highlighted and spoken.
- **Help at the airport:** verified airport maps and passenger contacts are available on demand. A large staff-help card shows the selected language and English when needed.
- **Journey problem assistance:** travelers can type or dictate a problem. SerpApi AI returns a short answer using the current flight and journey context.
- **Family sharing:** a read-only link shows the latest journey step without exposing the update credential.

## How it works

1. Upload a travel document or enter a flight number and departure date.
2. Review the extracted fields and select the current flight segment.
3. Query the configured live flight providers.
4. Normalize values, retain provenance, and detect conflicts.
5. Calculate one next action from live status, local time, and completed steps.
6. Refresh every few minutes and announce meaningful changes.

## Key design details

### Document evidence

Nutrient returns structured fields with confidence, page, and bounding-box data. OneStep validates field formats and marks uncertain values for review. The extraction schema requests journey fields and excludes passenger names, PNRs, ticket numbers, passport data, payment data, and barcode contents.

### Semantic multi-source merge

Each selected field retains its provider, source-update time, retrieval time, quality, candidates, and conflict state. Field-aware comparison recognizes equivalent values such as:

- `Terminal 1` and `T1`
- `Gate 08` and `8`
- a check-in area and the same area with a counter range

AeroDataBox can provide a route hint before SerpApi and VariFlight candidate matching. Each provider has an independent timeout and five-minute cache. One successful provider can keep the journey available during a partial failure. A failed refresh preserves the last successful snapshot.

### Next-action engine

The backend uses deterministic rules for `CHECK_IN`, `BAG_DROP`, `SECURITY`, `GO_TO_GATE`, `WAIT`, `BOARD_NOW`, `IN_FLIGHT`, `BAGGAGE_CLAIM`, `EXIT_AIRPORT`, and `ASK_FOR_HELP`.

Decisions use server time, airport-local offset, scheduled and operational times, data freshness, live status, and completed journey steps. Cancellation, stale near-departure data, a missing gate, and overdue boarding trigger a stop or help action.

### Verified airport resources

Airport lookup begins with a canonical OurAirports identity. Search and AI results pass official-domain checks, source binding, page-purpose classification, ranking, and conflict handling. Phone numbers are normalized with libphonenumber. Recruitment, media, parking, cargo, and specialized-service pages are filtered from general passenger contacts.

### Accessible help and sharing

The UI uses large controls, high contrast, ARIA live regions, focus management, and automatic speech. Selecting **Not sure** or **Ask staff for help** opens a large card. Chinese and Spanish cards include English for airport staff. The app speaks a short request to show the card.

Family sharing uses separate view and update tokens. Only token hashes are stored. Sessions expire after 48 hours and can be revoked. The family page polls the read-only snapshot every 13 seconds.

## Technology

| Function | Technology and implementation |
|---|---|
| Backend and APIs | Java 17, Spring Boot 4.1, Spring MVC, validation, and Spring `RestClient` |
| Document recognition | Nutrient Data Extraction API, Jackson schema, confidence thresholds, page indexes, and bounding boxes |
| Live flight data | SerpApi Flight Result, AeroDataBox through RapidAPI, and VariFlight Tripmatch through JSON-RPC/MCP |
| Data reconciliation | `CompletableFuture`, per-provider timeouts, TTL caches, request de-duplication, semantic normalization, provenance, and conflict records |
| Journey decisions | Deterministic Java rules, `Instant`, `OffsetDateTime`, airport-local offsets, status, freshness, and journey progress |
| Airport resources | SerpApi Search, AI Mode, Maps, OurAirports identities, official-source verification, classifiers, ranking, and libphonenumber |
| Journey problem AI | SerpApi AI Mode, a context-aware prompt, short-answer extraction, voice input, and a local safety fallback |
| Frontend and accessibility | HTML, CSS, vanilla JavaScript, locale dictionaries, Web Speech APIs, ARIA, and responsive layouts |
| Sharing and persistence | Spring Data JPA, H2, PostgreSQL, Flyway, hashed view/update tokens, expiry, and revocation |
| Offline resilience | Service Worker, saved journey snapshots, last-known instructions, and deferred share revocation |
| Deployment | Maven, a multi-stage Docker build, and Render Blueprint infrastructure |

## External integrations

### Nutrient

- **Role:** Understand an uploaded travel document.
- **User flow:** Upload a PDF, JPG, or PNG, then review the extracted segments and fields.
- **Verification:** Validate formats, apply confidence thresholds, preserve citations, and request traveler confirmation.
- **Result:** Convert common travel documents into reviewable journey evidence with multi-leg support.

### SerpApi

- **Role:** Supply live flight context, airport resources, and AI help for journey problems.
- **User flow:** Refresh flight data, request a map or passenger contact, or submit a typed or dictated problem.
- **Verification:** Check flight identity, reconcile airport identity, verify official sources, classify candidates, rank evidence, and limit AI answers for display and speech.
- **Result:** Provide fresher operational fields, verified airport resources, and concise journey assistance.

### Additional flight providers

| Provider | Contribution |
|---|---|
| AeroDataBox via RapidAPI | Flight/date lookup, route hints, quality, operational times, terminal, gate, check-in area, and arrival baggage belt |
| VariFlight Tripmatch MCP | `searchFlightsByNumber` through JSON-RPC/MCP over JSON or SSE |

## Run locally

Requirements: Java 17 and the included Maven Wrapper.

Set the keys for the integrations you want to use:

```text
NUTRIENT_API_KEY
SERPAPI_API_KEY
AERODATABOX_RAPIDAPI_KEY
VARIFLIGHT_API_KEY
```

VariFlight also requires `VARIFLIGHT_ENABLED=true`. It remains disabled by default.

Windows PowerShell:

```powershell
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '18080'
.\mvnw.cmd spring-boot:run
```

Open <http://127.0.0.1:18080/>. Integration readiness is available at <http://127.0.0.1:18080/api/integrations/status>. The endpoint returns `READY`, `MISSING`, or `DISABLED` and never exposes key values.

The local profile uses in-memory H2. Local share sessions end when the Spring Boot process restarts.

## API

- `GET /api/health` — health check.
- `GET /api/integrations/status` — integration readiness.
- `POST /api/documents/boarding-pass` — Nutrient document extraction and field evidence.
- `GET /api/flights/{flightNumber}?departureDate=YYYY-MM-DD&demo=false` — SerpApi flight adapter endpoint.
- `POST /api/journeys/resolve-flight` — document and live-provider reconciliation.
- `POST /api/journeys/next-action` — refreshed next-action calculation.
- `POST /api/journeys/refresh-flight` — forced provider refresh.
- `POST /api/assistance/airport-resources` — verified airport map and passenger contact lookup.
- `POST /api/assistance/journey-problem` — concise SerpApi AI journey help.
- `POST /api/share-sessions` — create a read-only family share.
- `GET`, `PUT`, `DELETE /api/share-sessions/{viewToken}` — read, update, or revoke a share.

## Demo and tests

The files in `test-fixtures/documents/` are synthetic and contain no real passenger data. They cover a booking confirmation, e-ticket, boarding pass, booking screenshot, and non-flight document.

Append `?demoScenarios=true` to show `NORMAL`, `GATE_CHANGE`, `DELAY`, `CANCEL`, and `STALE` scenarios. Use `?qaJourney=true` for a same-day local journey walkthrough.

Before a submission build:

```powershell
node --check src/main/resources/static/app.js
.\mvnw.cmd test
.\mvnw.cmd package
git diff --check
```

## Deploy on Render

`render.yaml` defines a Docker web service and PostgreSQL database. The container runs as a non-root user and uses `/api/health` for health checks.

1. Create a Render Blueprint from this repository.
2. Set `NUTRIENT_API_KEY`, `SERPAPI_API_KEY`, and `AERODATABOX_RAPIDAPI_KEY` when prompted.
3. Wait for `/api/health` to succeed, then open the generated HTTPS URL.

PostgreSQL preserves share sessions across application restarts. Review the current Render plan limits before relying on a demo database for durable storage.

## Current limitations

- Walking time, security queues, airline eligibility rules, and indoor navigation are outside the current data model.
- Dedicated bag-drop locations require an explicit provider field. A check-in area remains labelled as a check-in area.
- Browser speech quality depends on installed operating-system voices.
- Provider coverage, quotas, update times, and field availability vary.
- Missing or conflicting operational details should be confirmed with airport staff and airport displays.
