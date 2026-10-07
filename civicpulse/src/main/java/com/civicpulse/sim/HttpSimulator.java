package com.civicpulse.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class HttpSimulator implements TelemetrySource {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpSimulator.class);
    private final SimulatedFleet fleet;
    private final String baseUrl;
    private final long intervalMs;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private ScheduledExecutorService executor;

    public HttpSimulator(SimulatedFleet fleet, String baseUrl, long intervalMs, double timeCompression) {
        this.fleet = fleet;
        this.baseUrl = baseUrl;
        this.intervalMs = Math.max(20, (long) (intervalMs / Math.max(0.1, timeCompression)));
    }

    @Override
    public void start(Consumer<Map<String, Object>> ignored) {
        executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "civicpulse-http-simulator"));
        executor.scheduleAtFixedRate(() -> fleet.sample().forEach(this::post), 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void post(Map<String, Object> sample) {
        try {
            String body = json.writeValueAsString(sample);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/ingest"))
                    .timeout(Duration.ofSeconds(1)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(error -> {
                        LOGGER.warn("Simulated telemetry could not reach CivicPulse", error);
                        return null;
                    });
        } catch (Exception e) {
            LOGGER.warn("Simulated telemetry could not be encoded", e);
        }
    }

    @Override
    public void inject(String nodeLabel, String type, int durationSec) {
        fleet.inject(nodeLabel, type, durationSec);
    }

    @Override
    public void close() {
        if (executor != null) executor.shutdownNow();
    }
}