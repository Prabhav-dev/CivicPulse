# CivicPulse

CivicPulse is a simulation-only maintenance monitor for public infrastructure.
It accepts scalar sensor readings, strips identifiers in memory, compares
quantized live windows with per-node baselines, stores numeric deltas, and
produces maintenance alerts. No hardware integration is required.

## Run

Requires Java 21 or newer and Maven. From this directory:

```sh
mvn -q test
mvn package
java -jar target/civicpulse.jar
```

Open [http://localhost:7070](http://localhost:7070). The default fleet is
simulated and sends readings to the local HTTP endpoint. Without
`GEMINI_API_KEY`, alerts use the local template fallback. To enable Gemini,
export the key before launch; only anonymized delta fields are sent externally.

```sh
export GEMINI_API_KEY="your-key"
java -jar target/civicpulse.jar
```

The project's `pom.xml` includes the existing `Yggdrasil-Blind_Storage` Java
source tree as a Maven source root. Its source is not modified. The project
builds a single shaded runnable jar.

## Configuration

| Property | Default | Purpose |
| --- | --- | --- |
| `server.port` | `7070` | HTTP server port |
| `store.type` | `memory` | `memory` or `yggdrasil` delta store |
| `store.directory` | `data/yggdrasil` | Yggdrasil database and WAL directory |
| `store.max-records` | `10000` | In-memory delta retention limit |
| `yggdrasil.key` | demo key | 32 hex characters for the 16-byte Ascon key; replace for non-demo use |
| `anonymizer.salt` | demo salt | HMAC salt used to create stable neutral labels; replace for non-demo use |
| `diff.window-size` | `30` | Samples per metric window and baseline capacity |
| `diff.threshold-pct` | `25` | Minimum absolute percentage change |
| `diff.persistence-windows` | `2` | Consecutive anomalous windows before raising an alert |
| `diff.cooldown-ms` | `60000` | Per-node/metric alert cooldown |
| `diff.dropout-intervals` | `5` | Missed expected intervals before a per-metric dropout event |
| `diff.expected-interval-ms` | `1000` | Expected reporting interval used by dropout detection |
| `sim.enabled` | `true` | Start the simulated fleet |
| `sim.source` | `http` | `http` or `inprocess` telemetry source |
| `sim.interval-ms` | `1000` | Nominal simulator sample interval |
| `sim.time-compression` | `5` | Simulator sample speed multiplier |
| `sim.base-url` | `http://127.0.0.1:7070` | HTTP simulator target |
| `gemini.model` | `gemini-3.8-flash` | Gemini model for plain-language alerts |
| `gemini.timeout-ms` | `5000` | Maximum Gemini request wait |

Properties can be overridden with Java system properties, for example
`-Dstore.type=yggdrasil`. To run against Yggdrasil:

```sh
java -Dstore.type=yggdrasil -jar target/civicpulse.jar
```

`/status` reports server-received last-seen timestamps per metric; dropout
checks run periodically even when a node stops sending completely. The expected
interval is configurable and independent of simulator time compression.

HTTP routes: `POST /ingest`, `GET /alerts?limit=20`, `GET /status`,
`POST /sim/inject`, and `GET /deltas?node=<neutral-label>&limit=20`.
`POST /ingest` requires a non-blank `mac` or `deviceSerial`; identity-less
payloads receive HTTP 400 instead of being merged into a shared node.

## Architecture

```text
SimulatedFleet / GNU Radio
          |
          v
   POST /ingest --> Anonymizer --> Reading --> DiffEngine
                    (HMAC label)              |
                                               | threshold + persistence
                                               v
                                        DeltaRecord only
                                         /           \
                              memory / Yggdrasil     AlertService
                                                         |
                                            Gemini or template fallback
                                                         |
                           Dashboard <-- /status, /alerts, /deltas
```

## Limitations

- All fleet readings are synthetic; the backend does not connect to public
  infrastructure or control hardware.
- GNU Radio values are relative dB from fixed simulated amplitudes, not
  calibrated dBm. There is no AGC.
- Gemini is the only external data flow. It receives only neutral node labels,
  metric names, percentage changes, and timestamps from stored deltas. Raw
  readings, MACs, serials, and location fields are not sent to it.
- The checked-in anonymizer salt and Yggdrasil key are demo defaults; configure
  private values before using the project with any non-synthetic data.
- The per-process alert feed is not a separate durable event log. Deltas are
  durable only when `store.type=yggdrasil` is selected.
- The backend has no authentication or TLS; keep the demo bound to a trusted
  local network.