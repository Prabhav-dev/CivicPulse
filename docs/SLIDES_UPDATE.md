# Slide Update Text

This is replacement copy for the existing seven-slide `CivicPulse 2.pdf` deck.
It deliberately does not claim measured performance: the evaluation harness
and results were deferred at the user's request, so the Results slide below
must remain marked pending until a real run is completed.

## Slide 1 — CivicPulse

**A Lightweight Java System for Monitoring Infrastructure Health**

**Problem statement:** Public infrastructure operators need timely signals of
asset health. CivicPulse demonstrates a privacy-conscious way to detect
synthetic changes in bridge, utility-line, and streetlight telemetry and turn
those changes into maintenance prompts.

**Scope:** Simulation only. No public-service usage tracking, real sensors, or
field data.

## Slide 2 — System Architecture

**HTTP ingest is implemented.** Synthetic scalar readings and the optional
GNU Radio simulation sender post to `POST /ingest`. Javalin runs the local Java
API and dashboard. LoRaWAN and MQTT adapters are future work; they are not
implemented transports.

**Pipeline:** simulated scalar source → bounded HTTP input → keyed-HMAC
pseudonymization → per-node/per-metric windows → threshold and sequence-change
detection → memory or Yggdrasil delta store → template alert, or optional Gemini
summary → dashboard.

The in-process simulator is also available. No custom firmware or radio
transmission is used.

## Slide 3 — Privacy and External Data

Sensor labels are **pseudonyms**, not anonymized identities: a keyed HMAC makes
labels stable and joinable, and possession of the salt permits candidate
identity checks. Raw MAC, serial, fine GPS, unknown fields, and raw readings are
not stored as deltas.

Yggdrasil stores numeric delta records only. The optional Gemini path receives
neutral node label, metric, percentage change, and timestamp only. Without
`GEMINI_API_KEY`, the local template is used and no Gemini request is made.
Configure private salt and encryption key outside source files.

## Slide 4 — Storage Layer

**Yggdrasil Storage Module**

The standalone local Maven module contains the existing `Yggdrasil` Java
package: B+Tree index, heap/slotted pages, WAL, and Ascon-authenticated encrypted
pages. CivicPulse stores compact numeric delta JSON, not raw telemetry. An
11-bit stable label-hash bucket plus a 20-bit monotonic sequence bounds reads to
one key interval; exact labels are checked within the interval. Sequence
high-water marks are stored in `civicpulse-sequences.properties` without node
labels. Retention is currently unbounded.

**Storage limits:** coarse synchronized access; `WeakHashMap` cache; finite
metadata directory; no full ARIES transactions, undo, or isolation levels.

## Slide 5 — Detection

**The diff localizes sequence changes; it does not calculate magnitude.** Each
metric window is quantized to `LOW`, `NORMAL`, `ELEVATED`, or `HIGH` symbols.
`DiffUtils.diff` locates the first changed target position and can identify a
substantial stable-mean shape change. Signed percentage/absolute mean changes
against rolling and slow EWMA references decide threshold crossings.

```java
Patch<String> patch = DiffUtils.diff(baselineSymbols, liveSymbols);
boolean candidate = !patch.getDeltas().isEmpty()
        && (shortThresholdExceeded || longThresholdExceeded || shapeChanged);
int position = patch.getDeltas().get(0).getTarget().getPosition();
```

This is a logic sketch, not a verbatim compile-ready extract. Alert candidates
must persist for the configured windows and pass cooldown. Temperature and RF
use absolute thresholds; vibration uses percent by default. See
[`DETECTION.md`](DETECTION.md) for full semantics and limits.

## Slide 6 — Demonstration and Results

**Demonstration:** Run the synthetic fleet, inject a vibration spike, inspect
the delta and template/Gemini source label, then inject dropout and gradual
sensor drift. The optional GNU Radio flowgraph is simulated and relative-dB
only. Gemini text is a suggested, unverified maintenance prompt.

| Evaluation metric | Result |
| --- | --- |
| False positives per simulated hour, no injection | Pending; harness not run |
| Detection rate by anomaly and seed | Pending; harness not run |
| Detection latency, samples and simulated seconds | Pending; harness not run |

Do not replace “pending” with estimates. Add measured output only after running
the documented offline evaluation command and recording its commit hash.

**Example alert:** “Bridge-<label> recorded a sustained vibration increase.
This could reflect a sensor or operating-condition change; the available
vibration reading alone does not establish a structural cause. Verify the sensor
mounting and inspect the next readings.”

## Slide 7 — Limitations and Roadmap

- All readings are synthetic. There is no real infrastructure data or hardware.
- RF values are relative dB, not calibrated dBm; no AGC is used.
- API key access control is optional shared-secret gating, not full authN/Z;
  TLS is not configured. The server binds to loopback by default.
- Myers diff localizes quantized sequence changes; it is not a statistical
  cause-finding model. The system does not infer cross-sensor causality.
- Yggdrasil uses coarse locking, a `WeakHashMap` cache, and lacks ARIES-style
  transactional recovery. Delta retention is unbounded.
- The legacy `Yggdrasil` package name remains unchanged; renaming is future
  cleanup, not part of this submission.
- Future work: evaluate thresholds on synthetic runs; add bounded Yggdrasil
  retention; consider LoRaWAN/MQTT adapters and stronger API deployment
  controls. Neither adapter is currently implemented.

Keep the existing bibliography on this slide as a small footer, and retain the
NIST/Ascon and Myers citations only for the specific mechanisms they support.