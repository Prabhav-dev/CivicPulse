package com.civicpulse.alert;

import com.civicpulse.model.DeltaRecord;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class AlertService implements AlertGenerator, AutoCloseable {
    private final String apiKey;
    private final String model;
    private final long timeoutMs;
    private final TemplateAlertFallback fallback;
    private volatile Client client;
    private final ExecutorService requests = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "civicpulse-gemini");
        thread.setDaemon(true);
        return thread;
    });

    public AlertService(String apiKey, String model, long timeoutMs, TemplateAlertFallback fallback) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutMs = timeoutMs;
        this.fallback = fallback;
    }

    @Override
    public Alert create(DeltaRecord latest, List<DeltaRecord> recent) {
        if (apiKey == null || apiKey.isBlank()) return fallback.create(latest);
        Future<String> request = requests.submit(() -> {
            String prompt = prompt(recent);
            return client().models.generateContent(model, prompt, null).text();
        });
        try {
            String text = request.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (text == null || text.isBlank()) return fallback.create(latest);
            return new Alert(latest.nodeLabel(), latest.metric(), latest.pctChange(), latest.ts(), text.trim(), "GEMINI");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            request.cancel(true);
            return fallback.create(latest);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            request.cancel(true);
            return fallback.create(latest);
        }
    }

    private Client client() {
        Client current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    current = Client.builder().apiKey(apiKey)
                            .httpOptions(HttpOptions.builder().timeout((int) timeoutMs).build()).build();
                    client = current;
                }
            }
        }
        return current;
    }

    private static String prompt(List<DeltaRecord> records) {
        StringBuilder input = new StringBuilder("Anonymized sensor deltas:\n");
        for (DeltaRecord record : records) {
            input.append("node=").append(record.nodeLabel())
                    .append(", metric=").append(record.metric())
                    .append(", pctChange=").append(record.pctChange())
                    .append(", ts=").append(record.ts()).append('\n');
        }
        return "You are a city maintenance assistant. Given these sensor deltas, write a 2-sentence plain-language alert with a likely cause and a recommended action. Do not invent data that is not provided.\n\n"
                + input;
    }

    @Override
    public void close() {
        requests.shutdownNow();
    }
}