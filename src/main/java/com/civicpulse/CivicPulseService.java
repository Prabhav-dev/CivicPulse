package com.civicpulse;

import com.civicpulse.alert.Alert;
import com.civicpulse.alert.AlertGenerator;
import com.civicpulse.anonymize.Anonymizer;
import com.civicpulse.diff.DiffEngine;
import com.civicpulse.diff.NodeCapacityException;
import com.civicpulse.model.DeltaRecord;
import com.civicpulse.model.Reading;
import com.civicpulse.store.DeltaStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CivicPulseService {
    private static final Logger LOGGER = LoggerFactory.getLogger(CivicPulseService.class);
    private final Anonymizer anonymizer;
    private final DiffEngine diffEngine;
    private final DeltaStore store;
    private final AlertGenerator alertGenerator;
    private final Deque<Alert> alerts = new ArrayDeque<>();
    private final Object alertsLock = new Object();
    private final Object stateLock = new Object();
    private final AtomicBoolean queueOverflowLogged = new AtomicBoolean();
    private final AtomicBoolean nodeCapacityLogged = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ThreadPoolExecutor alertWorker = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), task -> {
                Thread thread = new Thread(task, "civicpulse-alert-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public CivicPulseService(Anonymizer anonymizer, DiffEngine diffEngine,
                             DeltaStore store, AlertGenerator alertGenerator) {
        this.anonymizer = anonymizer;
        this.diffEngine = diffEngine;
        this.store = store;
        this.alertGenerator = alertGenerator;
    }

    public List<DeltaRecord> ingest(Map<String, Object> raw) {
        Reading reading = anonymizer.anonymize(raw);
        List<DeltaRecord> deltas;
        try {
            synchronized (stateLock) {
                deltas = diffEngine.accept(reading);
                for (DeltaRecord delta : deltas) store.append(delta);
            }
        } catch (NodeCapacityException e) {
            if (nodeCapacityLogged.compareAndSet(false, true)) LOGGER.warn(e.getMessage());
            throw e;
        }
        for (DeltaRecord delta : deltas) enqueueAlert(delta);
        return deltas;
    }

    public List<DeltaRecord> checkDropouts(long receiveTimeMs) {
        List<DeltaRecord> deltas;
        synchronized (stateLock) {
            deltas = diffEngine.detectDropouts(receiveTimeMs);
            for (DeltaRecord delta : deltas) store.append(delta);
        }
        for (DeltaRecord delta : deltas) enqueueAlert(delta);
        return deltas;
    }

    private void enqueueAlert(DeltaRecord delta) {
        try {
            alertWorker.execute(() -> createAndPublish(delta));
        } catch (RejectedExecutionException e) {
            if (queueOverflowLogged.compareAndSet(false, true)) {
                LOGGER.error("Alert queue is full; additional alert-generation jobs will be dropped");
            }
        }
    }

    private void createAndPublish(DeltaRecord delta) {
        Alert alert = alertGenerator.create(delta, store.readRecent(delta.nodeLabel(), 5));
        synchronized (alertsLock) {
            alerts.addLast(alert);
            while (alerts.size() > 200) alerts.removeFirst();
        }
    }

    public List<Alert> recentAlerts(int limit) {
        synchronized (alertsLock) {
            int boundedLimit = Math.max(0, Math.min(limit, alerts.size()));
            List<Alert> result = new ArrayList<>(alerts);
            return List.copyOf(result.subList(result.size() - boundedLimit, result.size()));
        }
    }

    public Map<String, DiffEngine.NodeSnapshot> status() {
        return diffEngine.snapshot();
    }

    public List<DeltaRecord> readRecent(String nodeLabel, int count) {
        return store.readRecent(nodeLabel, count);
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        alertWorker.shutdown();
        try {
            if (!alertWorker.awaitTermination(6, TimeUnit.SECONDS)) alertWorker.shutdownNow();
        } catch (InterruptedException e) {
            alertWorker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        alertGenerator.close();
        store.close();
    }
}