# Yggdrasil Storage Module

This Maven module packages the existing Yggdrasil Java storage engine as
`com.civicpulse:yggdrasil-storage`. Its source package remains `Yggdrasil`; no
runtime libraries are required. The small `Yggdrasil.gui.EngineTelemetry` and
`EngineObserver` support classes are retained because storage operations call
them. The CivicPulse shaded jar excludes the Swing screens.

The module's JUnit 5 tests include the Ascon known-answer vector, WAL regression
and crash recovery, metadata/page authentication, and tamper-response checks.
Run them from the workspace root with `mvn -q -pl yggdrasil test`.

`Yggdrasil-Blind_Storage/` remains as the user's original nested checkout and is
not modified or required for a clean build. This module is the tracked,
standalone build copy.