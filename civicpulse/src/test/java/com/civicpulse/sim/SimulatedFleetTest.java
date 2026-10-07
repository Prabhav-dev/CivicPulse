package com.civicpulse.sim;

import com.civicpulse.anonymize.Anonymizer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SimulatedFleetTest {
    @Test
    void eachInjectionChangesItsIntendedSyntheticSignal() {
        assertInjectionChanges("VIBRATION_SPIKE", "vibrationRms", 0);
        assertInjectionChanges("TEMP_DROP", "tempC", 1);
        assertInjectionChanges("SENSOR_DRIFT", "tempC", 12);

        AtomicLong clock = new AtomicLong(10_000);
        Anonymizer anonymizer = new Anonymizer("fleet-test-salt");
        SimulatedFleet fleet = new SimulatedFleet(anonymizer, clock::get);
        Map<String, Object> baseline = fleet.sample().getFirst();
        String node = anonymizer.anonymize(baseline).nodeLabel();
        fleet.inject(node, "DROPOUT", 1);
        Map<String, Object> dropout = fleet.sample().getFirst();
        assertFalse(dropout.containsKey("vibrationRms"));
        assertFalse(dropout.containsKey("tempC"));
        assertFalse(dropout.containsKey("rfPower"));
    }

    @Test
    void injectionsExpireAndUnknownTargetsAreRejected() {
        AtomicLong clock = new AtomicLong(10_000);
        Anonymizer anonymizer = new Anonymizer("fleet-test-salt");
        SimulatedFleet fleet = new SimulatedFleet(anonymizer, clock::get);
        Map<String, Object> baseline = fleet.sample().getFirst();
        String node = anonymizer.anonymize(baseline).nodeLabel();
        fleet.inject(node, "DROPOUT", 1);
        assertFalse(fleet.sample().getFirst().containsKey("tempC"));
        clock.addAndGet(1001);
        assertTrue(fleet.sample().getFirst().containsKey("tempC"));

        assertThrows(IllegalArgumentException.class, () -> fleet.inject("Bridge-unknown", "DROPOUT", 1));
        assertThrows(IllegalArgumentException.class, () -> fleet.inject(node, "UNKNOWN", 1));
        assertThrows(IllegalArgumentException.class, () -> fleet.inject(node, "DROPOUT", 0));
    }

    private static void assertInjectionChanges(String type, String metric, double minimumChange) {
        AtomicLong clock = new AtomicLong(10_000);
        Anonymizer anonymizer = new Anonymizer("fleet-test-salt");
        SimulatedFleet fleet = new SimulatedFleet(anonymizer, clock::get);
        Map<String, Object> baseline = fleet.sample().getFirst();
        String node = anonymizer.anonymize(baseline).nodeLabel();
        double before = ((Number) baseline.get(metric)).doubleValue();
        fleet.inject(node, type, 10);
        double after = ((Number) fleet.sample().getFirst().get(metric)).doubleValue();
        if ("VIBRATION_SPIKE".equals(type)) assertTrue(after > before * 1.5);
        else if ("TEMP_DROP".equals(type)) assertTrue(after < before - Math.abs(minimumChange) / 2);
        else assertTrue(after > before + minimumChange / 2);
    }
}