package com.civicpulse.web;

import java.util.LinkedHashMap;
import java.util.Map;

public final class IngestValidator {
    private IngestValidator() { }

    public static Map<String, Object> validate(Map<String, Object> payload, long now, long timestampWindowMs) {
        Map<String, Object> validated = new LinkedHashMap<>(payload);
        validateMetric(validated, "vibrationRms", 0, 100);
        validateMetric(validated, "tempC", -60, 150);
        validateMetric(validated, "rfPower", -200, 50);
        validateMetric(validated, "rfPowerDb", -200, 50);

        Object timestamp = validated.get("ts");
        if (!(timestamp instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            validated.put("ts", now);
        } else {
            long candidate = number.longValue();
            long earliest = now - timestampWindowMs;
            long latest = now + timestampWindowMs;
            if (candidate < earliest || candidate > latest) validated.put("ts", now);
        }
        return validated;
    }

    private static void validateMetric(Map<String, Object> payload, String name, double minimum, double maximum) {
        Object value = payload.get(name);
        if (value == null) return;
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
        double measurement = number.doubleValue();
        if (!Double.isFinite(measurement)) {
            throw new IllegalArgumentException(name + " must be a finite number");
        }
        if (measurement < minimum || measurement > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}