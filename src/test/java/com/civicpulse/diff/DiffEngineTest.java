package com.civicpulse.diff;

import com.civicpulse.model.DeltaRecord;
import com.civicpulse.model.Reading;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffEngineTest {
    @Test
    void steadyNoisyReadingsDoNotAlert() {
        DiffEngine engine = new DiffEngine(10, 25, 2, 60_000);
        List<DeltaRecord> found = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            found.addAll(engine.accept(reading(1.0 + (i % 3 - 1) * 0.005, i * 1000L)));
        }
        assertTrue(found.isEmpty());
    }

    @Test
    void sustainedFortyPercentSpikeAlertsAfterPersistenceWindows() {
        DiffEngine engine = new DiffEngine(10, 25, 2, 60_000);
        for (int i = 0; i < 10; i++) engine.accept(reading(1.0, i * 1000L));

        for (int i = 10; i < 20; i++) assertTrue(engine.accept(reading(1.4, i * 1000L)).isEmpty());
        List<DeltaRecord> found = List.of();
        for (int i = 20; i < 30; i++) found = engine.accept(reading(1.4, i * 1000L));

        assertEquals(1, found.size());
        assertEquals("vibrationRms", found.getFirst().metric());
        assertEquals(40.0, found.getFirst().pctChange(), 0.01);
    }

    @Test
    void cooldownPreventsRepeatedAlerts() {
        DiffEngine engine = new DiffEngine(10, 25, 2, 60_000);
        for (int i = 0; i < 10; i++) engine.accept(reading(1.0, i * 1000L));
        for (int i = 10; i < 20; i++) engine.accept(reading(1.4, i * 1000L));
        List<DeltaRecord> found = List.of();
        for (int i = 20; i < 30; i++) found = engine.accept(reading(1.4, i * 1000L));
        assertEquals(1, found.size());

        int extraAlerts = 0;
        for (int i = 30; i < 70; i++) extraAlerts += engine.accept(reading(1.4, i * 1000L)).size();
        assertEquals(0, extraAlerts);
    }

    @Test
    void dropoutIsEmittedOncePerOutageAndRecoversUsingReceiveTime() {
        DiffEngine engine = new DiffEngine(2, 25, 2, 5_000, 2, 1_000);
        engine.accept(reading(1.0, 100, 0));
        engine.accept(reading(1.0, 200, 1_000));

        assertTrue(engine.accept(missingVibration(300, 2_000)).isEmpty());
        assertTrue(engine.accept(missingVibration(400, 3_000)).isEmpty());
        List<DeltaRecord> first = engine.accept(missingVibration(500, 3_001));
        assertEquals(1, first.size());
        assertEquals("DROPOUT", first.getFirst().kind());
        assertEquals("vibrationRms", first.getFirst().metric());
        assertEquals(3_001, first.getFirst().ts());
        assertTrue(engine.detectDropouts(5_000).isEmpty());

        engine.accept(reading(1.0, 600, 5_000));
        assertEquals(5_000, engine.snapshot().get("Bridge-A").metrics().get("vibrationRms").lastSeenTs());
        assertTrue(engine.accept(missingVibration(700, 7_000)).isEmpty());
        List<DeltaRecord> second = engine.accept(missingVibration(800, 7_001));
        assertEquals(0, second.size(), "cooldown suppresses an immediate second outage event");
        engine.accept(reading(1.0, 900, 8_000));
        List<DeltaRecord> afterCooldown = engine.detectDropouts(11_001);
        assertEquals(1, afterCooldown.stream().filter(delta -> delta.metric().equals("vibrationRms")).count());
        assertTrue(afterCooldown.stream().allMatch(delta -> delta.kind().equals("DROPOUT")));
        assertTrue(engine.detectDropouts(12_000).isEmpty());
    }

    @Test
    void nodeCapacityRejectsNewLabelsAndEvictsExpiredNodes() {
        DiffEngine engine = new DiffEngine(2, 25, 2, 0, 5, 1000, 1, 1000);
        engine.accept(new Reading("Bridge-A", "bridge", 1, 20, -50, 1000, 1000));
        assertThrows(NodeCapacityException.class,
                () -> engine.accept(new Reading("Bridge-B", "bridge", 1, 20, -50, 1500, 1500)));

        engine.accept(new Reading("Bridge-B", "bridge", 1, 20, -50, 2001, 2001));
        assertEquals(List.of("Bridge-B"), engine.snapshot().keySet().stream().sorted().toList());
    }

    @Test
    void absoluteTemperatureThresholdWorksNearZeroAndPreservesSignedDelta() {
        DiffEngine engine = metricEngine("tempC", "absolute", 4.0);
        for (int i = 0; i < 2; i++) engine.accept(metricReading("tempC", 0.5, i));
        engine.accept(metricReading("tempC", -4.2, 2));
        List<DeltaRecord> found = engine.accept(metricReading("tempC", -4.2, 3));

        assertEquals(1, found.size());
        assertEquals("absolute", found.getFirst().mode());
        assertEquals(-4.7, found.getFirst().absoluteChange(), 0.001);
    }

    @Test
    void absoluteDbThresholdDetectsNegativeShift() {
        DiffEngine engine = metricEngine("rfPowerDb", "absolute", 6.0);
        for (int i = 0; i < 2; i++) engine.accept(metricReading("rfPowerDb", -60, i));
        engine.accept(metricReading("rfPowerDb", -67, 2));
        List<DeltaRecord> found = engine.accept(metricReading("rfPowerDb", -67, 3));

        assertEquals(1, found.size());
        assertEquals(-7.0, found.getFirst().absoluteChange(), 0.001);
        assertTrue(found.getFirst().pctChange() < 0);
    }

    @Test
    void slowDriftEventuallyCrossesTheFrozenLongTermReference() {
        DiffEngine engine = new DiffEngine(4, 20, 1, 0, 5, 1000, 500, 3_600_000,
                java.util.Map.of("vibrationRms", new DiffEngine.Threshold("percent", 20)), 0.001, 1.0);
        for (int i = 0; i < 4; i++) engine.accept(reading(1.0, i));

        List<DeltaRecord> found = List.of();
        for (int window = 1; window <= 30 && found.isEmpty(); window++) {
            double level = 1.0 + window * 0.02;
            for (int sample = 0; sample < 4; sample++) {
                found = engine.accept(reading(level, 4L + (window - 1L) * 4 + sample));
            }
        }

        assertEquals(1, found.size());
        assertTrue(found.getFirst().pctChange() >= 20);
        assertTrue(found.getFirst().absoluteChange() > 0);
    }

    @Test
    void anomalousWindowDoesNotPoisonRollingOrLongTermBaseline() {
        DiffEngine engine = new DiffEngine(4, 20, 1, 0, 5, 1000, 500, 3_600_000,
                java.util.Map.of("vibrationRms", new DiffEngine.Threshold("percent", 20)), 0.001, 1.0);
        for (int i = 0; i < 4; i++) engine.accept(reading(1.0, i));
        List<DeltaRecord> found = List.of();
        for (int i = 4; i < 8; i++) found = engine.accept(reading(1.5, i));

        assertEquals(1, found.size());
        DiffEngine.MetricStats stats = engine.snapshot().get("Bridge-A").metrics().get("vibrationRms");
        assertEquals(1.0, stats.baselineMean(), 0.001);
        assertEquals(1.0, stats.longTermMean(), 0.001);
    }

    @Test
    void elapsedTimeWindowsCompleteOnDurationDespiteIrregularSamples() {
        DiffEngine engine = new DiffEngine(30, 25, 1, 0, 5, 1000, 500, 3_600_000,
                java.util.Map.of("vibrationRms", new DiffEngine.Threshold("percent", 25)),
                0.001, 1.0, 2);
        engine.accept(timeReading(1.0, 0));
        engine.accept(timeReading(1.0, 500));
        engine.accept(timeReading(1.0, 2000));
        engine.accept(timeReading(2.0, 2500));
        engine.accept(timeReading(2.0, 4100));
        List<DeltaRecord> found = engine.accept(timeReading(2.0, 4500));

        assertEquals(1, found.size());
        assertTrue(found.getFirst().pctChange() >= 25);
    }

    private static Reading reading(double vibration, long ts) {
        return reading(vibration, ts, ts);
    }

    private static Reading reading(double vibration, long ts, long receiveTs) {
        return new Reading("Bridge-A", "bridge", vibration, 20, -50, ts, receiveTs);
    }

    private static Reading missingVibration(long senderTs, long receiveTs) {
        return new Reading("Bridge-A", "bridge", Double.NaN, 20, -50, senderTs, receiveTs);
    }

    private static DiffEngine metricEngine(String metric, String mode, double threshold) {
        return new DiffEngine(2, 25, 1, 0, 5, 1000, 500, 3_600_000,
                java.util.Map.of(metric, new DiffEngine.Threshold(mode, threshold)));
    }

    private static Reading metricReading(String metric, double value, long timestamp) {
        double vibration = "vibrationRms".equals(metric) ? value : Double.NaN;
        double temperature = "tempC".equals(metric) ? value : Double.NaN;
        double rfPower = "rfPowerDb".equals(metric) ? value : Double.NaN;
        return new Reading("Bridge-A", "bridge", vibration, temperature, rfPower, timestamp, timestamp);
    }

    private static Reading timeReading(double vibration, long receiveTime) {
        return new Reading("Bridge-A", "bridge", vibration, Double.NaN, Double.NaN,
                receiveTime, receiveTime);
    }
}