package com.civicpulse.sim;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class InProcessSimulator implements TelemetrySource {
    private final SimulatedFleet fleet;
    private final long intervalMs;
    private final BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>(1000);
    private ScheduledExecutorService producer;
    private ExecutorService consumer;

    public InProcessSimulator(SimulatedFleet fleet, long intervalMs, double timeCompression) {
        this.fleet = fleet;
        this.intervalMs = Math.max(20, (long) (intervalMs / Math.max(0.1, timeCompression)));
    }

    @Override
    public void start(Consumer<Map<String, Object>> sink) {
        producer = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "civicpulse-sim-producer"));
        consumer = Executors.newSingleThreadExecutor(r -> new Thread(r, "civicpulse-sim-consumer"));
        consumer.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { sink.accept(queue.take()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        producer.scheduleAtFixedRate(() -> fleet.sample().forEach(queue::offer), 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void inject(String nodeLabel, String type, int durationSec) {
        fleet.inject(nodeLabel, type, durationSec);
    }

    @Override
    public void close() {
        if (producer != null) producer.shutdownNow();
        if (consumer != null) consumer.shutdownNow();
    }
}