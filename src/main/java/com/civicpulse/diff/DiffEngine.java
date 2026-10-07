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
        if (windowSize < 2 || thresholdPct < 0 || persistenceWindows < 1 || cooldownMs < 0
                    || dropoutIntervals < 1 || expectedIntervalMs < 1 || maxNodes < 1 || nodeTtlMs < 1
                    || !Double.isFinite(longTermAlpha) || longTermAlpha <= 0 || longTermAlpha > 1
                    || !Double.isFinite(longTermThresholdScale) || longTermThresholdScale < 0) {
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
            if (baseline.size() < windowSize) {
                addWindow(baseline, value);
                if (baseline.size() == windowSize) longTermReference = mean(baseline);
                return null;
            }

            live.addLast(value);
            if (live.size() < windowSize) return null;
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
            Patch<String> patch = DiffUtils.diff(symbols(baseline, baselineMean, stddev(baseline)),
                    symbols(live, baselineMean, stddev(baseline)));
                boolean candidate = !patch.getDeltas().isEmpty() && thresholdAnomaly;
            boolean useLongReference = longExceeded && !shortExceeded;
            double absoluteChange = useLongReference ? longAbsoluteChange : shortAbsoluteChange;
            double pctChange = useLongReference ? longPctChange : shortPctChange;

            if (!candidate) {
                consecutiveCandidates = 0;
                if (!thresholdAnomaly) {
                    for (double sample : live) addWindow(baseline, sample);
                    longTermReference += longTermAlpha * (liveMean - longTermReference);
                }
                live.clear();
                return null;
            }

            consecutiveCandidates++;
            boolean coolingDown = lastAlertTs != Long.MIN_VALUE && reading.ts() - lastAlertTs < cooldownMs;
            if (consecutiveCandidates < persistenceWindows || coolingDown) {
                if (consecutiveCandidates >= persistenceWindows) {
                    int position = patch.getDeltas().get(0).getTarget().getPosition();
                    live.clear();
                    return null;
                }
                live.clear();
                return null;
            }
            lastAlertTs = reading.ts();
            int position = patch.getDeltas().get(0).getTarget().getPosition();
            live.clear();
                return new DeltaRecord(reading.nodeLabel(), metric, pctChange, position, reading.ts(),
                    "SHIFT", absoluteChange, threshold.mode());
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
        double scale = Math.max(stddev, 1.0e-9);
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