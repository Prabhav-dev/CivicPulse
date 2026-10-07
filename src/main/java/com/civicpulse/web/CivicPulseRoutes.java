package com.civicpulse.web;

import com.civicpulse.CivicPulseService;
import com.civicpulse.diff.DiffEngine;
import com.civicpulse.model.Reading;
import com.civicpulse.sim.TelemetrySource;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CivicPulseRoutes {
    private CivicPulseRoutes() { }

    public static Javalin create(CivicPulseService service, TelemetrySource simulator, int port) {
        Javalin app = Javalin.create(config -> config.staticFiles.add("/public", Location.CLASSPATH));
        app.get("/", ctx -> ctx.redirect("/index.html"));
        app.post("/ingest", ctx -> {
            Map<String, Object> raw = ctx.bodyAsClass(Map.class);
            ctx.json(Map.of("accepted", true, "deltas", service.ingest(raw)));
        });
        app.get("/alerts", ctx -> {
            int limit = queryLimit(ctx.queryParam("limit"), 20);
            ctx.json(service.recentAlerts(limit));
        });
        app.get("/status", ctx -> {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, DiffEngine.NodeSnapshot> entry : service.status().entrySet()) {
                Reading reading = entry.getValue().latestReading();
                result.put(entry.getKey(), new NodeStatus(reading.nodeLabel(), reading.nodeType(), reading.ts(),
                        finite(reading.vibrationRms()), finite(reading.tempC()), finite(reading.rfPowerDb()),
                        entry.getValue().metrics()));
            }
            ctx.json(result);
        });
        app.get("/deltas", ctx -> {
            String node = ctx.queryParam("node");
            if (node == null || node.isBlank()) {
                ctx.status(400).json(Map.of("error", "node query parameter is required"));
                return;
            }
            ctx.json(service.readRecent(node, queryLimit(ctx.queryParam("limit"), 20)));
        });
        app.post("/sim/inject", ctx -> {
            if (simulator == null) {
                ctx.status(409).json(Map.of("error", "simulator is disabled"));
                return;
            }
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            try {
                Object node = request.get("node");
                Object type = request.get("type");
                Object duration = request.get("durationSec");
                if (node == null || type == null || !(duration instanceof Number seconds)) {
                    ctx.status(400).json(Map.of("error", "node, type, and numeric durationSec are required"));
                    return;
                }
                simulator.inject(node.toString(), type.toString(), seconds.intValue());
                ctx.json(Map.of("accepted", true));
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            }
        });
        app.exception(Exception.class, (error, ctx) -> {
            ctx.status(400).json(Map.of("error", "Invalid request"));
        });
        return app.start(port);
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
                              Map<String, DiffEngine.MetricStats> baselineStats) { }
}