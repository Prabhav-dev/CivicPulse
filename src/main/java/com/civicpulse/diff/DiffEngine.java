package com.civicpulse.diff;

import com.civicpulse.model.DeltaRecord;
import com.civicpulse.model.Reading;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.Patch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DiffEngine {
    private static final List<String> METRICS = List.of("vibrationRms", "tempC", "rfPowerDb");
    private static final int MAX_TIME_WINDOW_SAMPLES = 4096;
    private final int windowSize;
    private final double thresholdPct;
    private final int persistenceWindows;
    private final long cooldownMs;
    private final int dropoutIntervals;
    private final long expectedIntervalMs;
    private final int maxNodes;
    private final long nodeTtlMs;
    private final Map<String, Threshold> thresholds;
    private final double longTermAlpha;
    private final double longTermThresholdScale;
    private final long windowSeconds;
    private final double shapeChangeFraction;
    private final Map<String, Map<String, MetricState>> states = new LinkedHashMap<>();
    private final Map<String, Reading> latestReadings = new LinkedHashMap<>();

    public DiffEngine(int windowSize, double thresholdPct, int persistenceWindows, long cooldownMs) {
        this(windowSize, thresholdPct, persistenceWindows, cooldownMs, 5, 1000);
    }

    public DiffEngine(int windowSize, double thresholdPct, int persistenceWindows,
                      long cooldownMs, int dropoutIntervals, long expectedIntervalMs) {
        this(windowSize, thresholdPct, persistenceWindows, cooldownMs, dropoutIntervals,
            expectedIntervalMs, 500, 3_600_000);
        }

        public DiffEngine(int windowSize, double thresholdPct, int persistenceWindows,
                  long cooldownMs, int dropoutIntervals, long expectedIntervalMs,
                  int maxNodes, long nodeTtlMs) {
            this(windowSize, thresholdPct, persistenceWindows, cooldownMs, dropoutIntervals,
                expectedIntervalMs, maxNodes, nodeTtlMs, percentThresholds(thresholdPct));
            }

            public DiffEngine(int windowSize, double thresholdPct, int persistenceWindows,
                      long cooldownMs, int dropoutIntervals, long expectedIntervalMs,
                      int maxNodes, long nodeTtlMs, Map<String, Threshold> thresholds) {
                this(windowSize, thresholdPct, persistenceWindows, cooldownMs, dropoutIntervals,
                    expectedIntervalMs, maxNodes, nodeTtlMs, thresholds, 0.005, 1.0);
                }

                public DiffEngine(int windowSize, double thresholdPct, int persistenceWindows,
                          long cooldownMs, int dropoutIntervals, long expectedIntervalMs,
                          int maxNodes, long nodeTtlMs, Map<String, Threshold> thresholds,
                          double longTermAlpha, double longTermThresholdScale) {
                    this(windowSize, thresholdPct, persistenceWindows, cooldownMs, dropoutIntervals,
                        expectedIntervalMs, maxNodes, nodeTtlMs, thresholds, longTermAlpha,
                        longTermThresholdScale, 0);
                    }

                    public DiffEngine(int windowSize, double thresholdPct, int persistenceWindows,
                              long cooldownMs, int dropoutIntervals, long expectedIntervalMs,
                              int maxNodes, long nodeTtlMs, Map<String, Threshold> thresholds,
                              double longTermAlpha, double longTermThresholdScale, long windowSeconds) {
                        this(windowSize, thresholdPct, persistenceWindows, cooldownMs, dropoutIntervals,
                            expectedIntervalMs, maxNodes, nodeTtlMs, thresholds, longTermAlpha,
                            longTermThresholdScale, windowSeconds, 0.5);
                        }

                        public DiffEngine(int windowSize, double thresholdPct, int persistenceWindows,
                                  long cooldownMs, int dropoutIntervals, long expectedIntervalMs,
                                  int maxNodes, long nodeTtlMs, Map<String, Threshold> thresholds,
                                  double longTermAlpha, double longTermThresholdScale, long windowSeconds,
                                  double shapeChangeFraction) {
        if (windowSize < 2 || thresholdPct < 0 || persistenceWindows < 1 || cooldownMs < 0
                    || dropoutIntervals < 1 || expectedIntervalMs < 1 || maxNodes < 1 || nodeTtlMs < 1
                    || !Double.isFinite(longTermAlpha) || longTermAlpha <= 0 || longTermAlpha > 1
                        || !Double.isFinite(longTermThresholdScale) || longTermThresholdScale < 0
                            || windowSeconds < 0 || windowSeconds > Long.MAX_VALUE / 1000
                            || !Double.isFinite(shapeChangeFraction) || shapeChangeFraction < 0 || shapeChangeFraction > 1) {
            throw new IllegalArgumentException("Invalid diff-engine configuration");
        }
        this.windowSize = windowSize;
        this.thresholdPct = thresholdPct;
        this.persistenceWindows = persistenceWindows;
        this.cooldownMs = cooldownMs;
        this.dropoutIntervals = dropoutIntervals;
        this.expectedIntervalMs = expectedIntervalMs;
        this.maxNodes = maxNodes;
        this.nodeTtlMs = nodeTtlMs;
        this.thresholds = Map.copyOf(thresholds);
        this.longTermAlpha = longTermAlpha;
        this.longTermThresholdScale = longTermThresholdScale;
        this.windowSeconds = windowSeconds;
        this.shapeChangeFraction = shapeChangeFraction;
    }

    public synchronized List<DeltaRecord> accept(Reading reading) {
        evictExpired(reading.receivedTs());
        if (!latestReadings.containsKey(reading.nodeLabel()) && latestReadings.size() >= maxNodes) {
            throw new NodeCapacityException(maxNodes);
        }
        latestReadings.put(reading.nodeLabel(), reading);
        Map<String, MetricState> nodeStates = states.computeIfAbsent(reading.nodeLabel(), ignored -> new LinkedHashMap<>());
        List<DeltaRecord> deltas = new ArrayList<>();
        for (String metric : METRICS) {
            double value = value(reading, metric);
            MetricState state = nodeStates.get(metric);
            if (Double.isFinite(value)) {
                if (state == null) {
                    state = new MetricState();
                    nodeStates.put(metric, state);
                }
                DeltaRecord dropout = state.detectDropout(reading.nodeLabel(), metric, reading.receivedTs());
                if (dropout != null) deltas.add(dropout);
                state.lastSeenTs = reading.receivedTs();
                state.dropoutReported = false;
                DeltaRecord shift = state.accept(reading, metric, value);
                if (shift != null) deltas.add(shift);
            } else if (state != null) {
                DeltaRecord dropout = state.detectDropout(reading.nodeLabel(), metric, reading.receivedTs());
                if (dropout != null) deltas.add(dropout);
            }
        }
        return List.copyOf(deltas);
    }

    public synchronized List<DeltaRecord> detectDropouts(long receiveTimeMs) {
        evictExpired(receiveTimeMs);
        List<DeltaRecord> events = new ArrayList<>();
        for (Map.Entry<String, Map<String, MetricState>> node : states.entrySet()) {
            for (Map.Entry<String, MetricState> metric : node.getValue().entrySet()) {
                DeltaRecord event = metric.getValue().detectDropout(node.getKey(), metric.getKey(), receiveTimeMs);
                if (event != null) events.add(event);
            }
        }
        return List.copyOf(events);
    }

    private void evictExpired(long now) {
        List<String> expired = latestReadings.entrySet().stream()
                .filter(entry -> now - entry.getValue().receivedTs() > nodeTtlMs)
                .map(Map.Entry::getKey).toList();
        for (String nodeLabel : expired) {
            latestReadings.remove(nodeLabel);
            states.remove(nodeLabel);
        }
    }

    public synchronized Map<String, NodeSnapshot> snapshot() {
        Map<String, NodeSnapshot> result = new LinkedHashMap<>();
        for (Map.Entry<String, Reading> node : latestReadings.entrySet()) {
            Map<String, MetricStats> metrics = new LinkedHashMap<>();
            for (Map.Entry<String, MetricState> metric : states.getOrDefault(node.getKey(), Map.of()).entrySet()) {
                MetricState state = metric.getValue();
                metrics.put(metric.getKey(), new MetricStats(mean(state.baseline), stddev(state.baseline),
                    state.baseline.size(), state.latestValue, state.longTermReference, state.lastSeenTs,
                    state.isStale(System.currentTimeMillis())));
            }
            result.put(node.getKey(), new NodeSnapshot(node.getValue(), Map.copyOf(metrics)));
        }
        return Map.copyOf(result);
    }

    private final class MetricState {
        private final ArrayDeque<Double> baseline = new ArrayDeque<>();
        private final ArrayDeque<Double> live = new ArrayDeque<>();
        private int consecutiveCandidates;
        private long lastAlertTs = Long.MIN_VALUE;
        private long lastSeenTs = Long.MIN_VALUE;
        private long lastDropoutAlertTs = Long.MIN_VALUE;
        private long baselineStartTs = Long.MIN_VALUE;
        private long liveStartTs = Long.MIN_VALUE;
        private boolean dropoutReported;
        private double latestValue = Double.NaN;
        private double longTermReference = Double.NaN;

        private DeltaRecord detectDropout(String nodeLabel, String metric, long now) {
            if (lastSeenTs == Long.MIN_VALUE || dropoutReported
                || now - lastSeenTs <= expectedIntervalMs * dropoutIntervals) return null;
            dropoutReported = true;
            boolean coolingDown = lastDropoutAlertTs != Long.MIN_VALUE
                && now - lastDropoutAlertTs < cooldownMs;
            if (coolingDown) return null;
            lastDropoutAlertTs = now;
                return new DeltaRecord(nodeLabel, metric, 0, Math.max(0, baseline.size() - 1), now,
                    "DROPOUT", 0, "absolute");
        }

        private boolean isStale(long now) {
            return lastSeenTs != Long.MIN_VALUE
                && now - lastSeenTs > expectedIntervalMs * dropoutIntervals;
        }

        private DeltaRecord accept(Reading reading, String metric, double value) {
            latestValue = value;
            long receiveTime = reading.receivedTs();
            if (Double.isFinite(longTermReference)) {
                if (live.isEmpty()) liveStartTs = receiveTime;
                addLive(value);
                if (!liveWindowComplete(receiveTime)) return null;
            } else {
                if (baselineStartTs == Long.MIN_VALUE) baselineStartTs = receiveTime;
                addBaseline(value);
                if (!baselineWindowComplete(receiveTime)) return null;
                longTermReference = mean(baseline);
                return null;
            }

            double baselineMean = mean(baseline);
            double liveMean = mean(live);
                Threshold threshold = thresholds.getOrDefault(metric, new Threshold("percent", thresholdPct));
            double shortAbsoluteChange = liveMean - baselineMean;
            double shortPctChange = 100.0 * shortAbsoluteChange / denominator(metric, baselineMean);
            double longAbsoluteChange = liveMean - longTermReference;
            double longPctChange = 100.0 * longAbsoluteChange / denominator(metric, longTermReference);
            double shortChange = "absolute".equals(threshold.mode()) ? shortAbsoluteChange : shortPctChange;
            double longChange = "absolute".equals(threshold.mode()) ? longAbsoluteChange : longPctChange;
            boolean shortExceeded = Math.abs(shortChange) >= threshold.value();
            boolean longExceeded = Double.isFinite(longTermReference)
                    && Math.abs(longChange) >= threshold.value() * longTermThresholdScale;
                boolean thresholdAnomaly = shortExceeded || longExceeded;
                List<String> baselineSymbols = symbols(baseline, baselineMean, stddev(baseline));
                List<String> liveSymbols = symbols(live, baselineMean, stddev(baseline));
                Patch<String> patch = DiffUtils.diff(baselineSymbols, liveSymbols);
                boolean shapeChanged = !thresholdAnomaly && isShapeChange(baselineSymbols, liveSymbols);
                boolean candidate = !patch.getDeltas().isEmpty() && (thresholdAnomaly || shapeChanged);
            boolean useLongReference = longExceeded && !shortExceeded;
            double absoluteChange = useLongReference ? longAbsoluteChange : shortAbsoluteChange;
            double pctChange = useLongReference ? longPctChange : shortPctChange;

            if (!candidate) {
                consecutiveCandidates = 0;
                if (!thresholdAnomaly) {
                    if (windowSeconds == 0) {
                        for (double sample : live) addWindow(baseline, sample);
                    } else {
                        baseline.clear();
                        baseline.addAll(live);
                    }
                    longTermReference += longTermAlpha * (liveMean - longTermReference);
                }
                live.clear();
                liveStartTs = Long.MIN_VALUE;
                return null;
            }

            consecutiveCandidates++;
            boolean coolingDown = lastAlertTs != Long.MIN_VALUE && reading.ts() - lastAlertTs < cooldownMs;
            if (consecutiveCandidates < persistenceWindows || coolingDown) {
                if (consecutiveCandidates >= persistenceWindows) {
                    live.clear();
                    liveStartTs = Long.MIN_VALUE;
                    return null;
                }
                live.clear();
                liveStartTs = Long.MIN_VALUE;
                return null;
            }
            lastAlertTs = reading.ts();
            int position = patch.getDeltas().get(0).getTarget().getPosition();
            live.clear();
            liveStartTs = Long.MIN_VALUE;
                return new DeltaRecord(reading.nodeLabel(), metric, pctChange, position, reading.ts(),
                    shapeChanged ? "SHAPE_CHANGE" : "SHIFT", absoluteChange, threshold.mode());
        }

        private boolean baselineWindowComplete(long now) {
            if (windowSeconds == 0) return baseline.size() >= windowSize;
            return baseline.size() >= 2 && now - baselineStartTs >= windowSeconds * 1000;
        }

        private boolean liveWindowComplete(long now) {
            if (windowSeconds == 0) return live.size() >= windowSize;
            return now - liveStartTs >= windowSeconds * 1000;
        }

        private void addBaseline(double value) {
            if (windowSeconds == 0) addWindow(baseline, value);
            else {
                baseline.addLast(value);
                while (baseline.size() > MAX_TIME_WINDOW_SAMPLES) baseline.removeFirst();
            }
        }

        private void addLive(double value) {
            live.addLast(value);
            int limit = windowSeconds == 0 ? windowSize : MAX_TIME_WINDOW_SAMPLES;
            while (live.size() > limit) live.removeFirst();
        }
    }

    private void addWindow(ArrayDeque<Double> window, double value) {
        window.addLast(value);
        while (window.size() > windowSize) window.removeFirst();
    }

    private static double value(Reading reading, String metric) {
        return switch (metric) {
            case "vibrationRms" -> reading.vibrationRms();
            case "tempC" -> reading.tempC();
            case "rfPowerDb" -> reading.rfPowerDb();
            default -> Double.NaN;
        };
    }

    private static double denominator(String metric, double mean) {
        double floor = switch (metric) {
            case "vibrationRms" -> 0.01;
            case "tempC", "rfPowerDb" -> 1.0;
            default -> 1.0;
        };
        return Math.max(Math.abs(mean), floor);
    }

    private static List<String> symbols(Iterable<Double> values, double mean, double stddev) {
        double scale = Math.max(stddev, Math.max(Math.abs(mean) * 0.05, 0.01));
        List<String> result = new ArrayList<>();
        for (double value : values) {
            double z = (value - mean) / scale;
            if (z <= -2.0) result.add("LOW");
            else if (z >= 3.0) result.add("HIGH");
            else if (z >= 1.0) result.add("ELEVATED");
            else result.add("NORMAL");
        }
        return result;
    }

    private boolean isShapeChange(List<String> baselineSymbols, List<String> liveSymbols) {
        if (baselineSymbols.isEmpty() || baselineSymbols.size() != liveSymbols.size()) return false;
        int changed = 0;
        for (int i = 0; i < baselineSymbols.size(); i++) {
            if (!baselineSymbols.get(i).equals(liveSymbols.get(i))) changed++;
        }
        return (double) changed / baselineSymbols.size() >= shapeChangeFraction;
    }

    private static double mean(Iterable<Double> values) {
        double sum = 0;
        int count = 0;
        for (double value : values) {
            sum += value;
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    private static double stddev(Iterable<Double> values) {
        double average = mean(values);
        double sum = 0;
        int count = 0;
        for (double value : values) {
            double difference = value - average;
            sum += difference * difference;
            count++;
        }
        return count < 2 ? 0 : Math.sqrt(sum / count);
    }

    public record MetricStats(double baselineMean, double baselineStdDev, int sampleCount,
                              double latestValue, double longTermMean,
                              long lastSeenTs, boolean stale) { }
    public record NodeSnapshot(Reading latestReading, Map<String, MetricStats> metrics) { }

    private static Map<String, Threshold> percentThresholds(double thresholdPct) {
        return Map.of("vibrationRms", new Threshold("percent", thresholdPct),
                "tempC", new Threshold("percent", thresholdPct),
                "rfPowerDb", new Threshold("percent", thresholdPct));
    }

    public record Threshold(String mode, double value) {
        public Threshold {
            if (!"percent".equals(mode) && !"absolute".equals(mode)) {
                throw new IllegalArgumentException("Threshold mode must be percent or absolute");
            }
            if (!Double.isFinite(value) || value < 0) {
                throw new IllegalArgumentException("Threshold value must be finite and non-negative");
            }
        }
    }
}