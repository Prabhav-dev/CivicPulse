package com.civicpulse;

import com.civicpulse.alert.AlertService;
import com.civicpulse.alert.TemplateAlertFallback;
import com.civicpulse.anonymize.Anonymizer;
import com.civicpulse.diff.DiffEngine;
import com.civicpulse.store.InMemoryDeltaStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConcurrencyTest {
    @Test
    void concurrentIngestTracksEveryDistinctNodeWithoutExceptions() throws Exception {
        int workers = 8;
        int readingsPerWorker = 32;
        int totalNodes = workers * readingsPerWorker;
        CivicPulseService service = new CivicPulseService(new Anonymizer("concurrency-test-salt"),
                new DiffEngine(10, 25, 2, 60_000, 5, 1000, totalNodes, 3_600_000),
                new InMemoryDeltaStore(),
                new AlertService(null, "unused", 100, new TemplateAlertFallback()));
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                int workerId = worker;
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int reading = 0; reading < readingsPerWorker; reading++) {
                        int identity = workerId * readingsPerWorker + reading;
                        service.ingest(Map.of("nodeType", "bridge",
                                "mac", String.format("AA:BB:CC:%02X:%02X:%02X", identity >>> 16,
                                        identity >>> 8 & 0xff, identity & 0xff),
                                "vibrationRms", 1.0, "ts", (long) identity));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get();
            assertEquals(totalNodes, service.status().size());
        } finally {
            executor.shutdownNow();
            service.close();
        }
    }
}