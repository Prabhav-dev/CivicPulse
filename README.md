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
`GEMINI_API_KEY`, alerts use the local template fallback. Alerts are stored in
the local JSON-lines history and the latest 200 are restored on restart. Gemini
text is labeled suggested and unverified. To enable Gemini, export the key
before launch; only anonymized delta fields are sent externally.

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
| `server.host` | `127.0.0.1` | Bind address; loopback by default |
| `app.demo` | `true` | Demo-mode switch; `false` requires private secrets and disables injection |
| `request.max-body-bytes` | `8192` | Maximum streamed JSON request size |
| `ingest.timestamp-window-ms` | `300000` | Timestamp window around server time; outside it, server time is used |
| `store.type` | `memory` | `memory` or `yggdrasil` delta store |
| `store.fallback-to-memory` | `false` | Explicitly allow memory fallback if Yggdrasil startup fails |
| `store.directory` | `data/yggdrasil` | Yggdrasil database and WAL directory |
| `store.max-records` | `10000` | In-memory delta retention limit |
| `yggdrasil.key` | demo key | 32 hex characters for the 16-byte Ascon key; replace for non-demo use |
| `anonymizer.salt` | demo salt | HMAC salt used to create stable neutral labels; replace for non-demo use |
| `anonymizer.label-hex-length` | `12` | HMAC label suffix length in hexadecimal characters (8–64) |
| `diff.window-size` | `30` | Samples per metric window and baseline capacity |
| `diff.window-seconds` | `0` | `0` uses sample-count windows; positive values use elapsed-time windows |
| `diff.threshold-pct` | `25` | Minimum absolute percentage change |
| `diff.vibrationRms.mode` / `.threshold` | `percent` / `25` | Vibration threshold; legacy `diff.threshold-pct` remains the fallback |
| `diff.tempC.mode` / `.threshold` | `absolute` / `4.0` | Temperature threshold in °C |
| `diff.rfPowerDb.mode` / `.threshold` | `absolute` / `6.0` | RF threshold in relative dB |
| `diff.long-term-alpha` | `0.001` | EWMA reference update rate, applied only to quiet windows |
| `diff.long-term-threshold-scale` | `1.0` | Multiplier on each metric's threshold for long-term drift |
| `diff.shape-change-fraction` | `0.5` | Fraction of changed quantized positions to report a stable-mean shape change |
| `diff.persistence-windows` | `2` | Consecutive anomalous windows before raising an alert |
| `diff.cooldown-ms` | `60000` | Per-node/metric alert cooldown |
| `diff.dropout-intervals` | `5` | Missed expected intervals before a per-metric dropout event |
| `diff.expected-interval-ms` | `1000` | Expected reporting interval used by dropout detection |
| `diff.max-nodes` | `500` | Maximum tracked node labels; new nodes receive HTTP 429 at capacity |
| `diff.node-ttl-ms` | `3600000` | Idle time after which a node's state is evicted |
| `sim.enabled` | `true` | Start the simulated fleet |
| `sim.source` | `http` | `http` or `inprocess` telemetry source |
| `sim.interval-ms` | `1000` | Nominal simulator sample interval |
| `sim.time-compression` | `5` | Simulator sample speed multiplier |
| `sim.base-url` | `http://127.0.0.1:7070` | HTTP simulator target |
| `gemini.model` | `gemini-3.8-flash` | Gemini model for plain-language alerts |
| `gemini.timeout-ms` | `5000` | Maximum Gemini request wait |
| `alert.history-path` | `data/alerts.jsonl` | Bounded local alert history |

Properties can be overridden with Java system properties, for example
`-Dstore.type=yggdrasil`. To run against Yggdrasil:

```sh
java -Dstore.type=yggdrasil -jar target/civicpulse.jar
```
Yggdrasil initialization errors stop startup by default. Use
`-Dstore.fallback-to-memory=true` only when losing persistence for that run is
acceptable.

Secret precedence is environment variable, then Java system property, then
`application.properties`. Set `CIVICPULSE_ANONYMIZER_SALT` and
`CIVICPULSE_YGGDRASIL_KEY` for non-demo mode; `app.demo=false` refuses the
checked-in demo values. Set optional `CIVICPULSE_API_KEY` to require the
`X-API-Key` header on `POST /ingest`, `GET /deltas`, and `POST /sim/inject`.
Without it, the app logs a warning and these mutation/inspection routes remain
unauthenticated. Keep the default loopback bind unless you have added suitable
network controls.

`/status` reports server-received last-seen timestamps per metric; dropout
checks run periodically even when a node stops sending completely. The expected
interval is configurable and independent of simulator time compression.

HTTP routes: `POST /ingest`, `GET /alerts?limit=20`, `GET /status`,
`POST /sim/inject`, and `GET /deltas?node=<neutral-label>&limit=20`.
`POST /ingest` requires a non-blank `mac` or `deviceSerial`; identity-less
payloads receive HTTP 400 instead of being merged into a shared node.

## Architecture

Detection details and known algorithm limits are in [docs/DETECTION.md](docs/DETECTION.md).

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
- Sensor labels are keyed-HMAC pseudonyms, not anonymization. They are stable
  and joinable under a secret salt; anyone who obtains that salt and a candidate
  identifier can verify a match. Use a private salt and treat labels as
  pseudonymous data. The checked-in salt and Yggdrasil key are demo defaults.
- Alerts are retained in a bounded local history file. Deltas are durable only
  when `store.type=yggdrasil` is selected.
- The API key is optional shared-secret access control, not full authentication
  or authorization. There is no TLS; keep the demo bound to loopback or a
  trusted local network.