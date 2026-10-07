package com.civicpulse.alert;

import com.civicpulse.model.DeltaRecord;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class AlertService implements AlertGenerator, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(AlertService.class);
    private final String apiKey;
    private final String model;
    private final long timeoutMs;
    private final TemplateAlertFallback fallback;
    private final GeminiTextGenerator textGenerator;
    private final Consumer<String> warningSink;
    private final AtomicBoolean failureWarningLogged = new AtomicBoolean();
    private volatile Client client;
    private final ExecutorService requests = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "civicpulse-gemini");
        thread.setDaemon(true);
        return thread;
    });

    public AlertService(String apiKey, String model, long timeoutMs, TemplateAlertFallback fallback) {
        this(apiKey, model, timeoutMs, fallback, null, LOGGER::warn);
    }

    public AlertService(String apiKey, String model, long timeoutMs, TemplateAlertFallback fallback,
                        GeminiTextGenerator textGenerator, Consumer<String> warningSink) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutMs = timeoutMs;
        this.fallback = fallback;
        this.textGenerator = textGenerator == null ? this::generateWithSdk : textGenerator;
        this.warningSink = warningSink;
        if (apiKey != null && !apiKey.isBlank()) LOGGER.info("Gemini alert model configured: {}", model);
    }

    @Override
    public Alert create(DeltaRecord latest, List<DeltaRecord> recent) {
        if (apiKey == null || apiKey.isBlank()) return fallback.create(latest);
        Future<String> request = requests.submit(() -> {
            String prompt = prompt(recent);
            return textGenerator.generate(model, prompt);
        });
        try {
            String text = request.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (text == null || text.isBlank()) {
                warnOnce("Gemini returned no alert text; using the template fallback");
                return fallback.create(latest);
            }
                return new Alert(latest.nodeLabel(), latest.metric(), latest.pctChange(), latest.ts(), text.trim(),
                    "GEMINI", latest.absoluteChange(), latest.mode(), latest.kind());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            request.cancel(true);
            return fallback.create(latest);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            request.cancel(true);
            warnOnce("Gemini alert generation failed or timed out; using the template fallback");
            return fallback.create(latest);
        }
    }

    private String generateWithSdk(String selectedModel, String prompt) {
        return client().models.generateContent(selectedModel, prompt, null).text();
    }

    private void warnOnce(String message) {
        if (failureWarningLogged.compareAndSet(false, true)) warningSink.accept(message);
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
        return "You are a city maintenance assistant. Write exactly two plain-language sentences based only on the supplied sensor deltas. Describe any cause only as a possibility, cite the supplied metric and change, and recommend an action. When only one metric changed, do not speculate about a structural cause. State that advice is suggested and unverified. Do not invent readings, locations, identifiers, or evidence.\n\n"
                + input;
    }

    @Override
    public void close() {
        requests.shutdownNow();
    }
}