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

    private static Reading reading(double vibration, long ts) {
        return reading(vibration, ts, ts);
    }

    private static Reading reading(double vibration, long ts, long receiveTs) {
        return new Reading("Bridge-A", "bridge", vibration, 20, -50, ts, receiveTs);
    }

    private static Reading missingVibration(long senderTs, long receiveTs) {
        return new Reading("Bridge-A", "bridge", Double.NaN, 20, -50, senderTs, receiveTs);
    }
}