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

    private static Reading reading(double vibration, long ts) {
        return new Reading("Bridge-A", "bridge", vibration, 20, -50, ts);
    }
}