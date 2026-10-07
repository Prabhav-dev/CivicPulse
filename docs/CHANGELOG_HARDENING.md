# Hardening Changelog

## Baseline: 2026-10-07

- **What:** Read CivicPulse's README, demo, Maven configuration, all application
  and test sources, GNU Radio files, and the Yggdrasil README and bug-fix report.
- **Why:** Establish observed behavior before implementing the hardening prompt.
- **Files touched:** `docs/CHANGELOG_HARDENING.md`, `.gitignore`.
- **Test evidence:** `mvn -q test` passed: 8 tests, 0 failures, 0 errors, 0
  skipped, counted from `target/surefire-reports/TEST-*.xml`.
- **Environment:** Maven 3.9.12 and OpenJDK 25.0.4. The project compiler target
  is Java 21; a Java 21 runtime was not available for this baseline.
- **Network/API:** The baseline suite did not require a Gemini key. Offline
  dependency resolution has not yet been tested.
- **Assumption:** The nested Yggdrasil working tree remains its own repository
  and its Java package remains unchanged. Its tracked files will be included in
  the outer project as source inputs without staging its `.git` metadata.

## A1: Non-blocking alert generation

- **What:** Delta writes remain on the ingest state-mutation path; recent-store
  reads, alert generation, and alert publication use a bounded single-worker
  queue. The Gemini SDK client is initialized once and reused.
- **Why:** A slow external model or Yggdrasil scan must not hold up telemetry
  ingestion.
- **Files touched:** `src/main/java/com/civicpulse/CivicPulseService.java`,
  `src/main/java/com/civicpulse/alert/AlertGenerator.java`,
  `src/main/java/com/civicpulse/alert/AlertService.java`,
  `src/test/java/com/civicpulse/alert/AlertServiceTest.java`.
- **Test evidence:** `mvn -q test` passed; the two-second fake-generator test
  asserts ingest returns in under 100 ms and that the alert is later published.

## A2: Per-metric dropout detection

- **What:** Readings now retain server receive time separately from sender time.
  The engine tracks each observed metric's last-seen receive time, emits one
  `DROPOUT` delta after the configured missed-interval threshold, resets on
  recovery, and supports periodic scans when a node becomes completely silent.
  `/status` exposes last-seen timestamps and stale flags; the dashboard uses
  those server-side flags.
- **Why:** Missing samples must be observable even when other metrics or the
  entire node stop reporting.
- **Files touched:** `Reading`, `DeltaRecord`, `Anonymizer`, `DiffEngine`,
  `CivicPulseService`, `App`, `CivicPulseRoutes`, `TemplateAlertFallback`,
  `application.properties`, dashboard, tests, and README.
- **Test evidence:** `mvn -q test` passed. `DiffEngineTest` covers missed
  intervals, single event, cooldown, recovery, per-metric last-seen receive
  time, and subsequent dropout; `AlertServiceTest` verifies no-data wording.
- **Assumption:** Dropout timing uses `diff.expected-interval-ms`, not simulator
  time compression, so external telemetry sources can configure their cadence.

## A3: Require a device identity

- **What:** Reject ingestion when both `mac` and `deviceSerial` are missing or
  blank, with a clear HTTP 400 error. No fallback identity is derived from the
  node type.
- **Why:** Identity-less devices of one type must not silently merge.
- **Files touched:** `Anonymizer`, `CivicPulseRoutes`, `AnonymizerTest`, README.
- **Test evidence:** `mvn -q test` covers both absent and blank identities.

## A4: Bound input and node state

- **What:** Read JSON from the request stream with an 8 KB default cap; validate
  finite vibration, temperature, and RF ranges; normalize missing or out-of-
  window timestamps to server time; cap tracked nodes and evict nodes idle past
  the configured TTL. Capacity exhaustion is logged once and returned as 429.
- **Why:** Bound request and state memory and reject unsafe numeric values.
- **Files touched:** `RequestBodyReader`, `PayloadTooLargeException`,
  `IngestValidator`, `NodeCapacityException`, `DiffEngine`, `CivicPulseService`,
  routes, `App`, properties, tests, README.
- **Test evidence:** `IngestValidationTest` covers oversize, finite/range,
  optional metrics, and timestamp normalization; `DiffEngineTest` covers cap
  rejection and TTL eviction. `mvn -q test` passed.

## A5: Fail fast on storage configuration

- **What:** `store.type=yggdrasil` now stops initialization with a readable
  exception unless `store.fallback-to-memory=true` is explicitly selected.
  Unknown store types are rejected rather than silently becoming memory mode.
- **Why:** A requested durable store must not degrade unnoticed.
- **Files touched:** `App`, `application.properties`, `AppStoreConfigTest`,
  README.
- **Test evidence:** `AppStoreConfigTest` verifies malformed key startup failure
  and the explicit memory-fallback opt-in; `mvn -q test` passed.

## B1: Per-metric thresholds

- **What:** Keep vibration percentage thresholds and legacy `pctChange`, while
  temperature and relative RF use signed absolute change by default. Each
  delta also carries signed `absoluteChange` and the threshold `mode`.
- **Why:** Percent change is unstable near 0 °C and misleading for negative dB.
- **Files touched:** `DeltaRecord`, `DiffEngine`, `Alert`, alert generators,
  `App`, properties, tests, README.
- **Test evidence:** Tests cover temperature around zero and a negative RF
  shift; `mvn -q test` passed.

## B2: Protect against slow drift

- **What:** Keep a long-term EWMA reference alongside the rolling baseline.
  Either reference can trigger a threshold crossing; windows that cross either
  threshold are not absorbed into the rolling baseline or EWMA.
- **Why:** Prevent a sequence of individually small shifts from silently
  poisoning the reference and hiding a gradual anomaly.
- **Files touched:** `DiffEngine`, `App`, application properties, tests, README.
- **Test evidence:** `DiffEngineTest` covers a deterministic +2% per-window
  drift and verifies an anomalous window leaves both references unchanged;
  `mvn -q test` passed.

## B3: Optional elapsed-time windows

- **What:** `diff.window-seconds=0` preserves the existing sample-count mode;
  a positive value completes baseline and live windows by elapsed receive time.
  Time-window samples are capped at 4096 per metric.
- **Why:** Irregular source cadences should not imply a fixed sample duration.
- **Files touched:** `DiffEngine`, `App`, application properties, tests, README.
- **Test evidence:** Irregularly spaced samples complete the configured 2-second
  windows and detect a shift; existing sample-mode tests remain green under
  `mvn -q test`.