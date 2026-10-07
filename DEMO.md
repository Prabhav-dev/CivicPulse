# Five-Minute Demo

## 0:00–1:00: Start the synthetic fleet

Run `mvn package && java -jar target/civicpulse.jar` and open
`http://localhost:7070`. Point out the five simulated nodes and changing
temperature readings. The dashboard polls status and alerts every two seconds.

## 1:00–2:00: Prepare durable delta storage

Stop the app and restart it with
`java -Dstore.type=yggdrasil -jar target/civicpulse.jar`. The Yggdrasil
database and WAL are created under `data/yggdrasil`. No raw telemetry records
are written there; only compact JSON `DeltaRecord` bytes are inserted.

## 2:00–3:00: Inject a structural anomaly

Choose a bridge in the **Inject anomaly** controls and inject
`VIBRATION_SPIKE` for 20 seconds. The stream speeds up by the configured
time-compression factor. After the baseline window and two qualifying live
windows, a vibration delta clears the 25% threshold and appears in the alert
feed. The text is Gemini-generated when `GEMINI_API_KEY` is configured;
otherwise the source badge reads `TEMPLATE`.

## 3:00–4:00: Inspect the GNU Radio signal

Open GNU Radio Companion with the simulated flowgraph in
[`gnuradio/README_gnuradio.md`](gnuradio/README_gnuradio.md). Move the
interferer amplitude slider and watch the Frequency and Waterfall sinks. The
sender block reports one second of relative channel power as RF-only scalar
telemetry to `/ingest`. No transmitter or physical radio is used.

## 4:00–5:00: Verify privacy and stored deltas

Use the browser network panel to observe only anonymized status and alert
responses. For a node label shown on the dashboard, request
`/deltas?node=<label>&limit=20`. The returned records contain the neutral label,
metric, percentage change, position, and timestamp. Confirm `data/yggdrasil`
contains the database and WAL files; raw MAC, serial, fine location, and sensor
readings are not stored.