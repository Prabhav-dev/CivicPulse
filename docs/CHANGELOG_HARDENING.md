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