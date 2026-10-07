# GNU Radio Bridge

This bridge forwards synthetic complex samples to CivicPulse as one RF-power
measurement per second. It does not open a radio device or transmit anything.

## Radioconda flowgraph

1. Start CivicPulse and open `http://localhost:7070`.
2. In GNU Radio Companion, create a QT GUI flowgraph with a sample rate such as
   `32000` samples/second.
3. Add a Signal Source as the carrier and set a fixed amplitude such as `0.35`.
4. Add a Noise Source with a fixed amplitude such as `0.04`.
5. Add a second Signal Source as the interferer. Add a QT GUI Range variable and
   connect its value to the interferer amplitude; use a range from `0` to `0.5`
   with a step of `0.01`.
6. Add the carrier, noise, and interferer with an Add block.
7. Connect the sum to a Throttle using the same sample rate. Fan the throttled
   complex stream out to a QT GUI Frequency Sink, a QT GUI Waterfall Sink, and
   the CivicPulse RF telemetry Embedded Python Block.
8. Add an Embedded Python Block, open its source editor, and replace the sample
   implementation with `civicpulse_sender.py`. Set its URL to
   `http://127.0.0.1:7070/ingest`, its sample rate to the flowgraph rate, and
   connect its complex input to the Throttle output.
9. Run the flowgraph and move the interferer amplitude slider. The bridge sends
   scalar RF readings to the ordinary `/ingest` route; CivicPulse anonymizes the
   MAC before status, storage, or alert generation.

The sender calculates `10 log10(mean(|sample|^2))`. Its standard-library HTTP
POST runs on a daemon worker with a one-second timeout and a one-reading queue, so a slow or
unavailable backend cannot block GNU Radio's `work()` method. If the queue is
full, an old pending sample is kept and the new sample is dropped.

There is no AGC. Signal and noise amplitudes are fixed relative values, so the
reported dB is relative to this flowgraph and is not calibrated dBm or a field
measurement. A generated label is stable for this sender while the configured
anonymizer salt remains unchanged.