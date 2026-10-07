package com.civicpulse.alert;

import com.civicpulse.model.DeltaRecord;
import com.civicpulse.CivicPulseService;
import com.civicpulse.anonymize.Anonymizer;
import com.civicpulse.diff.DiffEngine;
import com.civicpulse.store.InMemoryDeltaStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlertServiceTest {
    @Test
    void missingKeyUsesTemplateFallback() {
        try (AlertService service = new AlertService(null, "gemini-3.8-flash", 5000, new TemplateAlertFallback())) {
            Alert alert = service.create(new DeltaRecord("Bridge-A", "vibrationRms", 40, 2, 1000), List.of());
            assertEquals("TEMPLATE", alert.source());
            assertEquals("Bridge-A", alert.nodeLabel());
            assertTrue(alert.text().contains("40.0% increase in vibrationRms"));
        }
    }

    @Test
    void dropoutUsesNoDataTemplate() {
        Alert alert = new TemplateAlertFallback().create(
                new DeltaRecord("Bridge-A", "vibrationRms", 0, 1, 1000, "DROPOUT"));
        assertEquals("TEMPLATE", alert.source());
        assertTrue(alert.text().contains("No data has arrived"));
    }

    @Test
    void fakeGeneratorSuccessUsesConfiguredModelAndOnlyDeltaPrompt() {
        List<String> warnings = new ArrayList<>();
        try (AlertService service = new AlertService("fake-key", "offline-model", 1000,
                new TemplateAlertFallback(), (model, prompt) -> {
                    assertEquals("offline-model", model);
                    assertTrue(prompt.contains("Bridge-A"));
                    assertTrue(prompt.contains("pctChange=40.0"));
                    assertTrue(prompt.contains("possibility"));
                    assertTrue(prompt.contains("only one metric changed"));
                    return "A possible sensor issue is indicated. Inspect the mounting and verify the next reading.";
                }, warnings::add)) {
            Alert alert = service.create(new DeltaRecord("Bridge-A", "vibrationRms", 40, 2, 1000),
                    List.of(new DeltaRecord("Bridge-A", "vibrationRms", 40, 2, 1000)));
            assertEquals("GEMINI", alert.source());
            assertEquals(0, warnings.size());
        }
    }

    @Test
    void timeoutAndExceptionFallBackAndWarnOnlyOnce() {
        List<String> warnings = new ArrayList<>();
        try (AlertService timeout = new AlertService("fake-key", "offline-model", 20,
                new TemplateAlertFallback(), (model, prompt) -> {
                    Thread.sleep(1000);
                    return "late";
                }, warnings::add)) {
            Alert alert = timeout.create(new DeltaRecord("Bridge-A", "tempC", 5, 2, 1000), List.of());
            assertEquals("TEMPLATE", alert.source());
        }
        assertEquals(1, warnings.size());

        warnings.clear();
        try (AlertService failing = new AlertService("fake-key", "offline-model", 1000,
                new TemplateAlertFallback(), (model, prompt) -> { throw new IllegalStateException("offline"); },
                warnings::add)) {
            assertEquals("TEMPLATE", failing.create(new DeltaRecord("Bridge-A", "tempC", 5, 2, 1000), List.of()).source());
            assertEquals("TEMPLATE", failing.create(new DeltaRecord("Bridge-A", "tempC", 5, 2, 2000), List.of()).source());
        }
        assertEquals(1, warnings.size());
    }

    @Test
    void blankGeneratorOutputFallsBackToTemplate() {
        try (AlertService service = new AlertService("fake-key", "offline-model", 1000,
                new TemplateAlertFallback(), (model, prompt) -> " ", ignored -> { })) {
            Alert alert = service.create(new DeltaRecord("Bridge-A", "tempC", 5, 2, 1000), List.of());
            assertEquals("TEMPLATE", alert.source());
        }
    }

    @Test
    void slowAlertGenerationDoesNotBlockIngestAndPublishesLater() throws Exception {
        CountDownLatch generatorStarted = new CountDownLatch(1);
        CountDownLatch alertPublished = new CountDownLatch(1);
        CivicPulseService service = new CivicPulseService(new Anonymizer("test-salt"),
                new DiffEngine(2, 25, 1, 0), new InMemoryDeltaStore(), (latest, recent) -> {
            generatorStarted.countDown();
            try { Thread.sleep(2000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            Alert generated = new TemplateAlertFallback().create(latest);
            alertPublished.countDown();
            return generated;
        });
        try {
            service.ingest(reading(1.0, 1));
            service.ingest(reading(1.0, 2));
            service.ingest(reading(2.0, 3));
            long start = System.nanoTime();
            service.ingest(reading(2.0, 4));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(elapsedMs < 100, "ingest blocked for " + elapsedMs + " ms");
            assertTrue(generatorStarted.await(1, TimeUnit.SECONDS));
            assertTrue(alertPublished.await(3, TimeUnit.SECONDS));
            assertEquals(1, service.recentAlerts(10).size());
        } finally {
            service.close();
        }
    }

    private static Map<String, Object> reading(double vibration, long ts) {
        return Map.of("nodeType", "bridge", "mac", "AA:BB:CC:DD:EE:FF",
                "vibrationRms", vibration, "tempC", 20.0, "rfPower", -50.0, "ts", ts);
    }
}