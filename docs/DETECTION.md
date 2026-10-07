# Detection Algorithm

## Window construction

Each node and metric has an independent rolling baseline and live window.
`diff.window-seconds=0` (the default) makes each window contain
`diff.window-size` samples. A positive `diff.window-seconds` uses server receive
time to close windows after the configured duration; in that mode each retained
metric window is capped at 4096 samples. Irregular cadence therefore changes the
number of samples in a time window, not its configured duration.

The initial baseline window seeds both a rolling reference and a long-term EWMA
reference. Quiet live windows replace the rolling baseline and update the EWMA
as `reference += alpha * (windowMean - reference)`. A window that crosses a
configured magnitude threshold is not learned into either reference.

## Quantization and Myers diff

Raw floating-point values are not compared for equality. Samples are quantized
to `LOW`, `NORMAL`, `ELEVATED`, or `HIGH` symbols using z-scores against the
rolling reference. The scale is `max(stddev, max(abs(mean) * 0.05, 0.01))`, so a
constant baseline does not turn arbitrarily small numeric differences into
`HIGH` symbols.

`DiffUtils.diff(baselineSymbols, liveSymbols)` has two concrete roles:

- Its first delta's target position is persisted as the changed-sequence
  position, locating where the observed symbol sequence first diverged.
- If the mean remains below its configured magnitude thresholds but at least
  `diff.shape-change-fraction` of corresponding symbols differ, the engine
  emits a `SHAPE_CHANGE` event.

The patch itself does not supply magnitude. The engine computes signed live
window mean changes against both the rolling and long-term references.

## Alert decision

A metric produces a shift candidate when the Myers patch is non-empty and
either the rolling or long-term reference crosses its configured threshold.
Vibration defaults to percentage change; temperature and relative RF default
to signed absolute change (°C and relative dB respectively). `pctChange` remains
in the record for compatibility, while `absoluteChange` and `mode` describe the
signed comparison. A shape candidate is the separate stable-mean exception
described above.

Candidates must persist for `diff.persistence-windows` consecutive windows and
pass the per-node/metric cooldown. A per-metric dropout event is emitted after
`diff.dropout-intervals` expected intervals without a finite reading; this path
uses server receive time and does not require another sample to arrive.

## Limits

- Windows are non-overlapping. A short transient that does not dominate a full
  live window can be diluted and missed.
- Quantization can hide changes smaller than a symbol boundary; mean thresholds
  still detect magnitude changes, but they are not a statistical hypothesis test.
- The EWMA can follow persistent quiet drift slowly by design. Tuning alpha,
  thresholds, window sizes, and persistence changes detection latency.
- Metrics are analyzed independently; this does not infer causal relationships
  across sensors or validate an infrastructure failure.
- Synthetic fleet performance is not evidence of field accuracy.