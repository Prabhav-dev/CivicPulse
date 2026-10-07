package com.civicpulse.anonymize;

import com.civicpulse.model.Reading;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

public final class Anonymizer {
    private final byte[] salt;

    public Anonymizer(String salt) {
        if (salt == null || salt.isBlank()) {
            throw new IllegalArgumentException("Anonymizer salt must not be blank");
        }
        this.salt = salt.getBytes(StandardCharsets.UTF_8);
    }

    public Reading anonymize(Map<String, Object> raw) {
        String type = normalizedType(raw.get("nodeType"));
        Object identity = firstPresent(raw, "mac", "deviceSerial");
        String label = label(type, identity == null ? type : identity.toString());
        return new Reading(label, type,
                number(raw.get("vibrationRms")),
                number(raw.get("tempC")),
                number(raw.containsKey("rfPowerDb") ? raw.get("rfPowerDb") : raw.get("rfPower")),
                timestamp(raw.get("ts")), System.currentTimeMillis());
    }

    private String label(String type, String identity) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] digest = mac.doFinal(identity.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            String prefix = switch (type) {
                case "bridge" -> "Bridge";
                case "utilityline" -> "Utility";
                case "streetlight" -> "Streetlight";
                default -> "Sensor";
            };
            return prefix + "-" + HexFormat.of().formatHex(digest, 0, 4).toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to pseudonymize sensor identity", e);
        }
    }

    private static String normalizedType(Object value) {
        if (value == null) return "unknown";
        String type = value.toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return switch (type) {
            case "bridge", "utilityline", "streetlight" -> type;
            default -> "unknown";
        };
    }

    private static Object firstPresent(Map<String, Object> values, String first, String second) {
        Object value = values.get(first);
        return value == null ? values.get(second) : value;
    }

    private static double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        return Double.NaN;
    }

    private static long timestamp(Object value) {
        return value instanceof Number number ? number.longValue() : System.currentTimeMillis();
    }
}