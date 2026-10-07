# HTTP API

The server binds to `127.0.0.1:7070` by default. JSON request bodies are capped
at `request.max-body-bytes` (8192 bytes by default). If `CIVICPULSE_API_KEY` is
set, send it as `X-API-Key` for `POST /ingest`, `GET /deltas`, and
`POST /sim/inject`. `/alerts` and `/status` remain readable without the key.

## `POST /ingest`

Accepts one scalar telemetry object. `mac` or `deviceSerial` is required and is
used only to derive a keyed neutral label; neither raw value is included in
`Reading`, stored deltas, alerts, or Gemini prompts.

| Field | Required | Accepted value |
| --- | --- | --- |
| `nodeType` | no | `bridge`, `utilityline`, `streetlight`; unknown values map to `unknown` |
| `mac` | one of | Non-blank device identity string |
| `deviceSerial` | one of | Non-blank device identity string |
| `vibrationRms` | no | Finite number from 0 through 100 |
| `tempC` | no | Finite number from −60 through 150 °C |
| `rfPower` or `rfPowerDb` | no | Finite number from −200 through 50 relative dB |
| `ts` | no | Epoch milliseconds; missing/non-numeric/outside the configured window uses server receive time |

Unknown fields are dropped. Missing metrics are permitted; a metric previously
seen and absent for more than `diff.dropout-intervals` expected intervals
produces a `DROPOUT` delta. Successful responses are JSON with `accepted` and
`deltas` fields.

```sh
curl -i -X POST http://127.0.0.1:7070/ingest \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: local-demo-key' \
  -d '{"nodeType":"bridge","mac":"AA:BB:CC:DD:EE:FF","vibrationRms":0.81,"tempC":24.1,"ts":1700000000000}'
```

## `GET /alerts?limit=20`

Returns recent alerts, newest at the end of the JSON array. `limit` defaults to
20 and is clamped to 0–100. Each alert has a neutral node label, metric, change,
timestamp, plain-language text, and `source` (`GEMINI` or `TEMPLATE`).

```sh
curl -i 'http://127.0.0.1:7070/alerts?limit=20'
```

## `GET /status`

Returns a map keyed by neutral node label. Each entry contains the latest
available metric values and `baselineStats`; each tracked metric includes
`lastSeenTs` and server-computed `stale`. Non-finite/missing measurements are
JSON `null`.

```sh
curl -i http://127.0.0.1:7070/status
```

## `POST /sim/inject`

Available only while simulator injection is enabled in demo mode. Body fields:
`node` (a neutral label returned by `/status`), `type` (one of
`VIBRATION_SPIKE`, `TEMP_DROP`, `SENSOR_DRIFT`, `DROPOUT`), and `durationSec`
(integer 1–3600).

```sh
curl -i -X POST http://127.0.0.1:7070/sim/inject \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: local-demo-key' \
  -d '{"node":"Bridge-0123456789AB","type":"VIBRATION_SPIKE","durationSec":20}'
```

## `GET /deltas?node=<neutral-label>&limit=20`

Returns recent stored delta records for one neutral label. `node` is required;
`limit` defaults to 20 and is clamped to 0–100. Deltas contain no raw sensor
fields or device identifiers. Both stores return matching entries in append
order, independent of sender timestamps. The Yggdrasil store retains deltas
without a cleanup policy, so its on-disk index grows until the operator
archives or replaces the configured data directory.

```sh
curl -i 'http://127.0.0.1:7070/deltas?node=Bridge-0123456789AB&limit=20' \
  -H 'X-API-Key: local-demo-key'
```

## Error responses

| Status | Meaning |
| --- | --- |
| `400` | Malformed JSON, missing device identity, invalid metric, or invalid injection request |
| `401` | Missing or incorrect API key when API-key protection is enabled |
| `409` | Simulator injection is disabled |
| `413` | Request body exceeds the configured byte limit |
| `429` | Maximum tracked-node count reached |