package com.civicpulse.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IngestValidationTest {
    @Test
    void rejectsBodyLargerThanConfiguredLimit() {
        byte[] body = new byte[8193];
        assertThrows(PayloadTooLargeException.class,
                () -> RequestBodyReader.readJson(new ByteArrayInputStream(body), 8192));
    }

    @Test
    void acceptsOptionalMetricsAtRangeBoundaries() {
        long now = 1_700_000_000_000L;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeType", "bridge");
        payload.put("mac", "AA:BB:CC:DD:EE:FF");
        payload.put("vibrationRms", 0.0);
        payload.put("tempC", -60.0);
        payload.put("rfPower", 50.0);
        payload.put("ts", now);

        assertEquals(now, IngestValidator.validate(payload, now, 300_000).get("ts"));
        assertFalse(IngestValidator.validate(Map.of("nodeType", "streetlight", "mac", "01"), now, 300_000)
                .containsKey("vibrationRms"));
    }

    @Test
    void rejectsOutOfRangeAndNonFiniteMeasurements() {
        assertInvalid("vibrationRms", -0.1, "vibrationRms must be between 0.0 and 100.0");
        assertInvalid("tempC", 150.1, "tempC must be between -60.0 and 150.0");
        assertInvalid("rfPower", -200.1, "rfPower must be between -200.0 and 50.0");
        assertInvalid("tempC", Double.POSITIVE_INFINITY, "tempC must be a finite number");
        assertInvalid("rfPowerDb", "-62", "rfPowerDb must be a finite number");
    }

    @Test
    void replacesMissingInvalidAndOutOfWindowTimestampsWithServerTime() {
        long now = 1_700_000_000_000L;
        assertEquals(now, IngestValidator.validate(Map.of("ts", now - 600_000), now, 300_000).get("ts"));
        assertEquals(now, IngestValidator.validate(Map.of("ts", "not-a-time"), now, 300_000).get("ts"));
        assertEquals(now, IngestValidator.validate(Map.of("ts", Double.NaN), now, 300_000).get("ts"));
    }

    private static void assertInvalid(String field, Object value, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mac", "AA:BB:CC:DD:EE:FF");
        payload.put(field, value);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> IngestValidator.validate(payload, System.currentTimeMillis(), 300_000));
        assertEquals(message, error.getMessage());
    }
}