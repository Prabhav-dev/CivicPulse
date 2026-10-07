package com.civicpulse.sim;

import com.civicpulse.anonymize.Anonymizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;

public final class SimulatedFleet {
    private final Anonymizer anonymizer;
    private final LongSupplier clock;
    private final Random random = new Random(42);
    private final List<Node> nodes = List.of(
            new Node("bridge", "AA:00:00:00:00:01", "BR-101", "47.6101,-122.3301", 0.16, 18.5, -58),
            new Node("bridge", "AA:00:00:00:00:02", "BR-102", "47.6121,-122.3321", 0.19, 20.0, -61),
            new Node("utilityline", "BB:00:00:00:00:01", "UT-201", "47.6141,-122.3341", 0.08, 31.0, -55),
            new Node("streetlight", "CC:00:00:00:00:01", "SL-301", "47.6161,-122.3361", 0.035, 22.0, -66),
            new Node("streetlight", "CC:00:00:00:00:02", "SL-302", "47.6181,-122.3381", 0.04, 23.0, -63));
    private final Map<String, Injection> injections = new HashMap<>();
    private final Map<String, String> labelsByMac = new HashMap<>();

    public SimulatedFleet(Anonymizer anonymizer) {
        this(anonymizer, System::currentTimeMillis);
    }

    public SimulatedFleet(Anonymizer anonymizer, LongSupplier clock) {
        this.anonymizer = anonymizer;
        this.clock = clock;
        for (Node node : nodes) {
            labelsByMac.put(node.mac, anonymizer.anonymize(Map.of("nodeType", node.type, "mac", node.mac)).nodeLabel());
        }
    }

    public synchronized List<Map<String, Object>> sample() {
        long now = clock.getAsLong();
        List<Map<String, Object>> samples = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("nodeType", node.type);
            raw.put("mac", node.mac);
            raw.put("deviceSerial", node.serial);
            raw.put("gpsFine", node.gpsFine);
            raw.put("vibrationRms", Math.max(0, node.vibration + random.nextGaussian() * node.vibration * 0.035));
            raw.put("tempC", node.temperature + Math.sin(now / 90_000.0) * 1.5 + random.nextGaussian() * 0.2);
            raw.put("rfPower", node.rfPower + random.nextGaussian() * 1.2);
            raw.put("ts", now);
            Injection injection = injections.get(node.mac);
            if (injection != null && now <= injection.expiresAt) apply(raw, injection.type);
            else if (injection != null) injections.remove(node.mac);
            samples.add(raw);
        }
        return List.copyOf(samples);
    }

    public synchronized void inject(String nodeLabel, String type, int durationSec) {
        if (durationSec < 1 || durationSec > 3600) throw new IllegalArgumentException("durationSec must be 1..3600");
        String mac = labelsByMac.entrySet().stream()
                .filter(entry -> entry.getValue().equals(nodeLabel))
                .map(Map.Entry::getKey).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown simulated node"));
        if (!List.of("VIBRATION_SPIKE", "TEMP_DROP", "SENSOR_DRIFT", "DROPOUT").contains(type)) {
            throw new IllegalArgumentException("Unknown anomaly type");
        }
        injections.put(mac, new Injection(type, clock.getAsLong() + durationSec * 1000L));
    }

    public List<String> nodeLabels() {
        return List.copyOf(labelsByMac.values());
    }

    private static void apply(Map<String, Object> reading, String type) {
        switch (type) {
            case "VIBRATION_SPIKE" -> reading.compute("vibrationRms", (key, value) -> ((Number) value).doubleValue() * 1.7);
            case "TEMP_DROP" -> reading.compute("tempC", (key, value) -> ((Number) value).doubleValue() - 14.0);
            case "SENSOR_DRIFT" -> reading.compute("tempC", (key, value) -> ((Number) value).doubleValue() + 12.0);
            case "DROPOUT" -> {
                reading.remove("vibrationRms");
                reading.remove("tempC");
                reading.remove("rfPower");
            }
            default -> throw new IllegalArgumentException("Unknown anomaly type");
        }
    }

    private record Node(String type, String mac, String serial, String gpsFine,
                        double vibration, double temperature, double rfPower) { }
    private record Injection(String type, long expiresAt) { }
}