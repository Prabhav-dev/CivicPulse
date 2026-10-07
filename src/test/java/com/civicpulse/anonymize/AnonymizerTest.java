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

    @Test
    void rejectsMissingOrBlankDeviceIdentity() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> anonymizer.anonymize(Map.of("nodeType", "bridge")));
        assertEquals("mac or deviceSerial is required to identify a sensor", missing.getMessage());

        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> anonymizer.anonymize(Map.of("nodeType", "bridge", "mac", " ", "deviceSerial", "")));
        assertEquals(missing.getMessage(), blank.getMessage());
    }

    @Test
    void labelLengthIsConfigurableAndDefaultsToTwelveHexCharacters() {
        Reading defaultLength = anonymizer.anonymize(Map.of("nodeType", "bridge", "mac", "A1"));
        Reading customLength = new Anonymizer("test-salt", 16)
                .anonymize(Map.of("nodeType", "bridge", "mac", "A1"));
        assertEquals(12, defaultLength.nodeLabel().substring(defaultLength.nodeLabel().indexOf('-') + 1).length());
        assertEquals(16, customLength.nodeLabel().substring(customLength.nodeLabel().indexOf('-') + 1).length());
    }
}