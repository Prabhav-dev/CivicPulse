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
  and its Java package remains unchanged. A standalone source copy is used by
  the outer Maven build; the original checkout is not part of the build.

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

## B4: Honest Myers-diff role

- **What:** The first Myers target-change position localizes sequence divergence.
  A separate symbol-position fraction can flag stable-mean shape changes.
  Constant baselines now use a 5%-of-mean / 0.01 scale floor, and the unused
  cooldown-branch position local was removed.
- **Why:** Separate structural sequence evidence from mean magnitude and avoid
  explosive z-scores when baseline standard deviation is zero.
- **Files touched:** `DiffEngine`, `TemplateAlertFallback`, `App`, properties,
  tests, `docs/DETECTION.md`, README.
- **Test evidence:** Tests cover low-level constant-baseline perturbation and a
  stable-mean alternating shape change with localized patch position;
  `mvn -q test` passed.

## C: Privacy and access controls

- **What:** Resolve salt/key/API secret values as environment > JVM property >
  properties file; refuse demo defaults with `app.demo=false`; lengthen labels
  to configurable 12-hex HMAC pseudonyms; optionally require `X-API-Key` for
  ingest, delta reads, and injection; bind to `127.0.0.1` by default and gate
  simulator injection on demo/simulator enablement.
- **Why:** Keep credentials out of source, reduce label collision risk, and
  make local-only and optional access-control defaults explicit.
- **Files touched:** `AppConfig`, `Anonymizer`, `App`, routes, properties,
  README, and configuration/anonymizer/route tests.
- **Test evidence:** `AppConfigTest`, `AnonymizerTest`, and live
  `CivicPulseRoutesTest`; `mvn -q test` passed with 30 total tests at this
  checkpoint.
- **Privacy note:** Labels remain pseudonyms; a keyed HMAC label is not
  irreversible anonymization.

## D: Template-first alerts and durable history

- **What:** Templates remain the no-key path. The Gemini client is reused; its
  configurable model is logged; failures, timeout, and blank responses fall
  back, with one warning per service instance. A fake generator supports
  offline tests. The prompt treats causes as possibilities and forbids
  structural speculation from one metric. The latest 200 alerts are persisted
  to a local JSON-lines file and loaded on restart. Gemini text is marked
  suggested and unverified in the dashboard.
- **Why:** External generation must remain optional, bounded, evidence-limited,
  and unable to break local demo operation.
- **Files touched:** Alert service/generator/history, CivicPulse service, app,
  properties, alert tests, dashboard, README, DEMO.
- **Test evidence:** Fake success, timeout, exception, and blank output; one
  warning across repeated failures; file-history restart round trip. `mvn -q
  test` passed with 34 tests at this checkpoint.

## F: Multi-module build and hygiene

- **What:** Replace the build-helper source root with parent, `yggdrasil`, and
  `civicpulse` Maven modules. The storage module has no runtime dependencies;
  the app shade excludes Swing screens. Five storage self-checks are JUnit 5
  tests. Add MIT project license, contribution notes, optional CI, and route
  documentation. The original nested checkout remains ignored and untouched.
- **Why:** Provide a clean reactor build, real module boundary, and documented
  reproducible validation without rewriting nested history.
- **Files touched:** Parent/module POMs, `civicpulse/`, `yggdrasil/`, `.gitignore`,
  removed `.gitmodules` pointer, `LICENSE`, `CONTRIBUTING.md`, CI, API docs,
  README and changelog.
- **Test evidence:** `mvn -q test` passed with 41 tests; `mvn -q package`
  produced `target/civicpulse.jar`; jar inspection confirmed telemetry support
  classes are present and Swing panels are absent.
- **RunTests:** The original nested checkout remains unmodified, including its
  legacy `RunTests` entry point. The standalone module's five named checks run
  through JUnit under the root Maven reactor.

## E: Bounded Yggdrasil delta index

- **What:** Add an inclusive B+Tree range API. Delta keys use an 11-bit stable
  node-label hash bucket and a 20-bit monotonic sequence shared within that
  bucket. Numeric bucket high-water marks persist in
  `civicpulse-sequences.properties`; range reads visit only that bucket and
  filter exact labels. Both store implementations return append order. Yggdrasil
  retention is unbounded and documented. The new store refuses a non-empty
  legacy random-key database without a sequence catalog rather than risking
  collisions.
- **Why:** Replace a full-index `readRecent` scan with a bounded ordered range
  while safely handling hash collisions and process restarts.
- **Files touched:** Standalone Yggdrasil `BPlusTree`, `YggdrasilDeltaStore`,
  sequence catalog logic, range/contract tests, README, API docs.
- **Test evidence:** B+Tree inclusive-range test; Yggdrasil two-label bucket
  collision, append ordering, restart high-water and persistence test; memory
  append-order test. `mvn -q -pl civicpulse -am test` passed.

## G: Behavior and contract coverage

- **What:** Add cooldown-reset, simulator injection/expiry/rejection,
  concurrent-ingest, and shared memory/Yggdrasil store append-order tests. The
  shared Yggdrasil contract also verifies restart persistence; bounded memory
  retention has its own test because Yggdrasil retention is intentionally
  documented as unbounded.
- **Why:** Make the hardened contracts executable without Gemini, hardware, or
  network access.
- **Files touched:** Diff, simulator, route, store, and concurrency tests;
  `SimulatedFleet` clock injection; this changelog.
- **Test evidence:** At this checkpoint `mvn -q test` passed with 50 tests,
  0 failures, 0 errors, 0 skipped. The command counts both Maven modules.