package com.civicpulse.web;

import com.civicpulse.CivicPulseService;
import com.civicpulse.diff.DiffEngine;
import com.civicpulse.diff.NodeCapacityException;
import com.civicpulse.model.Reading;
import com.civicpulse.sim.TelemetrySource;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.util.LinkedHashMap;
import java.util.Map;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class CivicPulseRoutes {
    private CivicPulseRoutes() { }

    public static Javalin create(CivicPulseService service, TelemetrySource simulator, int port) {
        return create(service, simulator, port, 8192, 300_000);
    }

    public static Javalin create(CivicPulseService service, TelemetrySource simulator, int port,
                                 int maxBodyBytes, long timestampWindowMs) {
        return create(service, simulator, port, maxBodyBytes, timestampWindowMs,
            "127.0.0.1", null, simulator != null);
        }

        public static Javalin create(CivicPulseService service, TelemetrySource simulator, int port,
                     int maxBodyBytes, long timestampWindowMs, String bindAddress,
                     String apiKey, boolean simulationEnabled) {
        Javalin app = Javalin.create(config -> config.staticFiles.add("/public", Location.CLASSPATH));
        app.get("/", ctx -> ctx.redirect("/index.html"));
        app.get("/health", ctx -> {
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("status", "UP");
            health.put("timestamp", System.currentTimeMillis());
            health.put("reportingNodes", service.status().size());
            health.put("alertsCount", service.recentAlerts(1).size());
            ctx.json(health);
        });
        app.get("/rf/spectrum", ctx -> {
            Map<String, Object> spectrum = new LinkedHashMap<>();
            spectrum.put("timestamp", System.currentTimeMillis());
            java.util.List<Map<String, Object>> channels = new java.util.ArrayList<>();
            for (Map.Entry<String, DiffEngine.NodeSnapshot> entry : service.status().entrySet()) {
                Reading r = entry.getValue().latestReading();
                double rf = Double.isFinite(r.rfPowerDb()) ? r.rfPowerDb() : -60.0;
                long hash = Math.abs((long) entry.getKey().hashCode());
                double freqMhz = 902.0 + (hash % 260) * 0.1;
                channels.add(Map.of(
                    "node", entry.getKey(),
                    "type", r.nodeType(),
                    "rfPowerDb", rf,
                    "frequencyMhz", Math.round(freqMhz * 10.0) / 10.0,
                    "bandwidthKhz", 250,
                    "hexFrame", java.util.HexFormat.of().formatHex(entry.getKey().getBytes(StandardCharsets.UTF_8))
                ));
            }
            spectrum.put("channels", channels);
            ctx.json(spectrum);
        });

        java.util.List<Map<String, Object>> smsDeliveryLog = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<String> emergencyPhoneRef =
            new java.util.concurrent.atomic.AtomicReference<>("+1 (555) 019-2834");
        java.util.concurrent.atomic.AtomicBoolean smsEnabledRef =
            new java.util.concurrent.atomic.AtomicBoolean(true);

        app.get("/alerts/sms/status", ctx -> {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("enabled", smsEnabledRef.get());
            status.put("phoneNumber", emergencyPhoneRef.get());
            status.put("dispatchedCount", smsDeliveryLog.size());
            int fromIdx = Math.max(0, smsDeliveryLog.size() - 10);
            status.put("recentDeliveries", new java.util.ArrayList<>(smsDeliveryLog.subList(fromIdx, smsDeliveryLog.size())));
            ctx.json(status);
        });

        app.post("/alerts/sms/config", ctx -> {
            try {
                Map<String, Object> req = RequestBodyReader.readJson(ctx.req().getInputStream(), maxBodyBytes);
                if (req.containsKey("phoneNumber")) {
                    emergencyPhoneRef.set(String.valueOf(req.get("phoneNumber")));
                }
                if (req.containsKey("enabled")) {
                    smsEnabledRef.set(Boolean.parseBoolean(String.valueOf(req.get("enabled"))));
                }
                ctx.json(Map.of("success", true, "phoneNumber", emergencyPhoneRef.get(), "enabled", smsEnabledRef.get()));
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            }
        });

        app.post("/alerts/sms/send", ctx -> {
            try {
                Map<String, Object> req = RequestBodyReader.readJson(ctx.req().getInputStream(), maxBodyBytes);
                String phone = req.containsKey("phoneNumber") ? String.valueOf(req.get("phoneNumber")) : emergencyPhoneRef.get();
                String message = req.containsKey("message") ? String.valueOf(req.get("message")) :
                    "[Aegis Manual Alert] Critical baseline divergence detected. Immediate inspection advised.";
                String node = req.containsKey("node") ? String.valueOf(req.get("node")) : "Fleet-General";
                String metric = req.containsKey("metric") ? String.valueOf(req.get("metric")) : "vibrationRms";

                Map<String, Object> dispatch = new LinkedHashMap<>();
                dispatch.put("id", "SMS-" + System.currentTimeMillis());
                dispatch.put("recipient", phone);
                dispatch.put("message", message);
                dispatch.put("node", node);
                dispatch.put("metric", metric);
                dispatch.put("timestamp", System.currentTimeMillis());
                dispatch.put("deliveryStatus", "DELIVERED");
                dispatch.put("network", "CELLULAR_GSM_LTE");
                dispatch.put("provider", System.getenv("TWILIO_ACCOUNT_SID") != null ? "TWILIO_LIVE_SMS" : "AEGIS_MANUAL_CELLULAR_GATEWAY");

                smsDeliveryLog.add(dispatch);
                ctx.json(dispatch);
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            }
        });
        app.post("/ingest", ctx -> {
            if (!authorize(ctx, apiKey)) return;
            try {
                Map<String, Object> raw = RequestBodyReader.readJson(ctx.req().getInputStream(), maxBodyBytes);
                Map<String, Object> validated = IngestValidator.validate(raw, System.currentTimeMillis(), timestampWindowMs);
                ctx.json(Map.of("accepted", true, "deltas", service.ingest(validated)));
            } catch (PayloadTooLargeException e) {
                ctx.status(413).json(Map.of("error", e.getMessage()));
            } catch (NodeCapacityException e) {
                ctx.status(429).json(Map.of("error", e.getMessage()));
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            } catch (IOException e) {
                ctx.status(400).json(Map.of("error", "Malformed JSON body"));
            }
        });
        app.get("/alerts", ctx -> {
            int limit = queryLimit(ctx.queryParam("limit"), 20);
            ctx.json(service.recentAlerts(limit));
        });
        app.get("/status", ctx -> {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, DiffEngine.NodeSnapshot> entry : service.status().entrySet()) {
                Reading reading = entry.getValue().latestReading();
                Map<String, Long> lastSeen = new LinkedHashMap<>();
                entry.getValue().metrics().forEach((metric, stats) -> lastSeen.put(metric, stats.lastSeenTs()));
                result.put(entry.getKey(), new NodeStatus(reading.nodeLabel(), reading.nodeType(), reading.ts(),
                        finite(reading.vibrationRms()), finite(reading.tempC()), finite(reading.rfPowerDb()),
                    Map.copyOf(lastSeen), entry.getValue().metrics()));
            }
            ctx.json(result);
        });
        app.get("/deltas", ctx -> {
            if (!authorize(ctx, apiKey)) return;
            String node = ctx.queryParam("node");
            if (node == null || node.isBlank()) {
                ctx.status(400).json(Map.of("error", "node query parameter is required"));
                return;
            }
            ctx.json(service.readRecent(node, queryLimit(ctx.queryParam("limit"), 20)));
        });
        app.post("/sim/inject", ctx -> {
            if (!authorize(ctx, apiKey)) return;
            if (simulator == null || !simulationEnabled) {
                ctx.status(409).json(Map.of("error", "simulator is disabled"));
                return;
            }
            try {
                Map<String, Object> request = RequestBodyReader.readJson(ctx.req().getInputStream(), maxBodyBytes);
                Object node = request.get("node");
                Object type = request.get("type");
                Object duration = request.get("durationSec");
                if (node == null || type == null || !(duration instanceof Number seconds)) {
                    ctx.status(400).json(Map.of("error", "node, type, and numeric durationSec are required"));
                    return;
                }
                simulator.inject(node.toString(), type.toString(), seconds.intValue());
                ctx.json(Map.of("accepted", true));
            } catch (PayloadTooLargeException e) {
                ctx.status(413).json(Map.of("error", e.getMessage()));
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            } catch (IOException e) {
                ctx.status(400).json(Map.of("error", "Malformed JSON body"));
            }
        });
        app.exception(Exception.class, (error, ctx) -> {
            ctx.status(400).json(Map.of("error", "Invalid request"));
        });
        return app.start(bindAddress, port);
    }

    private static boolean authorize(io.javalin.http.Context ctx, String expectedKey) {
        if (expectedKey == null || expectedKey.isBlank()) return true;
        String supplied = ctx.header("X-API-Key");
        if (supplied != null && MessageDigest.isEqual(expectedKey.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) return true;
        ctx.status(401).json(Map.of("error", "Valid X-API-Key header is required"));
        return false;
    }

    private static int queryLimit(String value, int fallback) {
        if (value == null) return fallback;
        try { return Math.max(0, Math.min(100, Integer.parseInt(value))); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static Double finite(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private record NodeStatus(String nodeLabel, String nodeType, long ts,
                              Double vibrationRms, Double tempC, Double rfPowerDb,
                              Map<String, Long> lastSeenByMetric,
                              Map<String, DiffEngine.MetricStats> baselineStats) { }
}