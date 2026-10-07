# Five-Minute Demo

## 0:00–0:40: Start the synthetic fleet

Run `mvn -q package && java -jar target/civicpulse.jar` and open
`http://localhost:7070`. Point out the five simulated nodes and changing
temperature readings. The dashboard polls status and alerts every two seconds.

## 0:40–1:10: Prepare durable delta storage

Stop the app and restart it with
`java -Dstore.type=yggdrasil -jar target/civicpulse.jar`. The Yggdrasil
database, WAL, and numeric sequence sidecar are created under `data/yggdrasil`.
No raw telemetry records are written there; only compact JSON `DeltaRecord`
bytes are inserted.

## 1:10–2:00: Inject a structural anomaly

Choose a bridge in the **Inject anomaly** controls and inject
`VIBRATION_SPIKE` for 20 seconds. The stream speeds up by the configured
time-compression factor. After the baseline window and two qualifying live
windows, a vibration delta clears the 25% threshold and appears in the alert
feed. The text is Gemini-generated when `GEMINI_API_KEY` is configured;
otherwise the source badge reads `TEMPLATE`.

## 2:00–2:45: Verify dropout detection

On the same node, inject `DROPOUT` for 20 seconds. The simulator omits its
measurements while identity and timestamp fields continue arriving. After five
expected intervals, the server emits per-metric no-data deltas and marks those
metrics stale. When the injection expires, measurements return and last-seen
state recovers.

## 2:45–3:30: Observe slow sensor drift

Then inject `SENSOR_DRIFT` for 20 seconds on the utility line. Its temperature
offset ramps over the injection duration rather than stepping immediately. The
EWMA/absolute threshold and full-window persistence determine when an alert
appears; do not claim a fixed latency without a recorded evaluation.

## 3:30–4:20: Inspect the GNU Radio signal

Open GNU Radio Companion with the simulated flowgraph in
[`gnuradio/README_gnuradio.md`](gnuradio/README_gnuradio.md). Move the
interferer amplitude slider and watch the Frequency and Waterfall sinks. The
sender block reports one second of relative channel power as RF-only scalar
telemetry to `/ingest`. No transmitter or physical radio is used.

## 4:20–5:00: Verify privacy and stored deltas

Use the browser network panel to observe only pseudonymous status and alert
responses. For a node label shown on the dashboard, request
`/deltas?node=<label>&limit=20`. The returned records contain the neutral label,
metric, signed change and mode, position, kind, and timestamp. Confirm `data/yggdrasil`
contains the database and WAL files; raw MAC, serial, fine location, and sensor
readings are not stored. The latest 200 alerts reload from `data/alerts.jsonl`;
Gemini-sourced text is marked “Suggested, unverified.”

For a protected deployment using `CIVICPULSE_API_KEY`, send `X-API-Key` on
manual route calls. The default demo flow is intended to run on loopback without
that optional key.