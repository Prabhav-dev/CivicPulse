package com.civicpulse.anonymize;

import com.civicpulse.model.Reading;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnonymizerTest {
    private final Anonymizer anonymizer = new Anonymizer("test-salt");

    @Test
    void stripsIdentifiersDropsUnknownFieldsAndKeepsStableLabel() {
        Map<String, Object> first = Map.of(
                "nodeType", "bridge", "mac", "AA:BB:CC:DD:EE:FF",
                "deviceSerial", "serial-123", "gpsFine", "secret-location",
                "vibrationRms", 0.81, "privateNote", "drop-me", "ts", 1700000000000L);
        Map<String, Object> repeated = Map.of("nodeType", "bridge", "mac", "aa:bb:cc:dd:ee:ff");

        Reading reading = anonymizer.anonymize(first);

        assertEquals(anonymizer.anonymize(repeated).nodeLabel(), reading.nodeLabel());
        assertTrue(reading.nodeLabel().startsWith("Bridge-"));
        assertFalse(reading.nodeLabel().contains("AA:BB"));
        assertEquals("bridge", reading.nodeType());
        assertTrue(Double.isNaN(reading.tempC()));
        assertFalse(reading.toString().contains("serial-123"));
        assertFalse(reading.toString().contains("secret-location"));
        assertFalse(reading.toString().contains("drop-me"));
    }

    @Test
    void acceptsRfOnlyPayload() {
        Reading reading = anonymizer.anonymize(Map.of(
                "nodeType", "streetlight", "mac", "01:02:03:04:05:06", "rfPower", -62.3));
        assertEquals(-62.3, reading.rfPowerDb());
        assertTrue(Double.isNaN(reading.vibrationRms()));
        assertTrue(Double.isNaN(reading.tempC()));
    }
}