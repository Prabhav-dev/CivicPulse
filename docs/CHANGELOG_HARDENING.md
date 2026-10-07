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