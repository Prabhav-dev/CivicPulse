package com.civicpulse;

import com.civicpulse.alert.AlertService;
import com.civicpulse.alert.TemplateAlertFallback;
import com.civicpulse.anonymize.Anonymizer;
import com.civicpulse.diff.DiffEngine;
import com.civicpulse.sim.HttpSimulator;
import com.civicpulse.sim.InProcessSimulator;
import com.civicpulse.sim.SimulatedFleet;
import com.civicpulse.sim.TelemetrySource;
import com.civicpulse.store.DeltaStore;
import com.civicpulse.store.InMemoryDeltaStore;
import com.civicpulse.store.YggdrasilDeltaStore;
import com.civicpulse.web.CivicPulseRoutes;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class App {
    private static final Logger LOGGER = LoggerFactory.getLogger(App.class);

    private App() { }

    public static void main(String[] args) {
        AppConfig config = new AppConfig();
        Anonymizer anonymizer = new Anonymizer(config.get("anonymizer.salt", "demo-salt"));
        DeltaStore store = createStore(config);
        AlertService alertService = new AlertService(System.getenv("GEMINI_API_KEY"),
                config.get("gemini.model", "gemini-3.8-flash"),
                config.getLong("gemini.timeout-ms", 5000), new TemplateAlertFallback());
        long expectedIntervalMs = config.getLong("diff.expected-interval-ms", 1000);
        DiffEngine diffEngine = new DiffEngine(config.getInt("diff.window-size", 30),
                config.getDouble("diff.threshold-pct", 25),
                config.getInt("diff.persistence-windows", 2),
            config.getLong("diff.cooldown-ms", 60_000),
            config.getInt("diff.dropout-intervals", 5), expectedIntervalMs);
        CivicPulseService service = new CivicPulseService(anonymizer, diffEngine, store, alertService);
        SimulatedFleet fleet = new SimulatedFleet(anonymizer);
        TelemetrySource simulator = createSimulator(config, fleet);
        Javalin app;
        try {
            app = CivicPulseRoutes.create(service, simulator, config.getInt("server.port", 7070));
        } catch (RuntimeException e) {
            service.close();
            throw e;
        }
        if (simulator != null) simulator.start(service::ingest);
        ScheduledExecutorService dropoutMonitor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "civicpulse-dropout-monitor");
            thread.setDaemon(true);
            return thread;
        });
        dropoutMonitor.scheduleAtFixedRate(() -> service.checkDropouts(System.currentTimeMillis()),
                expectedIntervalMs, expectedIntervalMs, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (simulator != null) simulator.close();
            dropoutMonitor.shutdownNow();
            app.stop();
            service.close();
        }, "civicpulse-shutdown"));
        LOGGER.info("CivicPulse listening on http://localhost:{}", config.getInt("server.port", 7070));
    }

    private static DeltaStore createStore(AppConfig config) {
        String type = config.get("store.type", "memory");
        if ("yggdrasil".equalsIgnoreCase(type)) {
            try {
                return new YggdrasilDeltaStore(config.get("store.directory", "data/yggdrasil"),
                        config.get("yggdrasil.key", "00112233445566778899aabbccddeeff"));
            } catch (Exception e) {
                LOGGER.error("Yggdrasil initialization failed; using in-memory storage for this run", e);
            }
        } else if (!"memory".equalsIgnoreCase(type)) {
            LOGGER.warn("Unknown store.type '{}'; using memory", type);
        }
        return new InMemoryDeltaStore(config.getInt("store.max-records", 10_000));
    }

    private static TelemetrySource createSimulator(AppConfig config, SimulatedFleet fleet) {
        if (!config.getBoolean("sim.enabled", true)) return null;
        String source = config.get("sim.source", "http");
        long interval = config.getLong("sim.interval-ms", 1000);
        double compression = config.getDouble("sim.time-compression", 5);
        if ("inprocess".equalsIgnoreCase(source)) return new InProcessSimulator(fleet, interval, compression);
        return new HttpSimulator(fleet, config.get("sim.base-url", "http://127.0.0.1:7070"), interval, compression);
    }
}