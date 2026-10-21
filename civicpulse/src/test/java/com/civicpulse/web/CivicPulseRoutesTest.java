package com.civicpulse.web;

import com.civicpulse.CivicPulseService;
import com.civicpulse.alert.AlertService;
import com.civicpulse.alert.TemplateAlertFallback;
import com.civicpulse.anonymize.Anonymizer;
import com.civicpulse.diff.DiffEngine;
import com.civicpulse.model.DeltaRecord;
import com.civicpulse.store.InMemoryDeltaStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CivicPulseRoutesTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void enforcesApiKeyAndValidatesRequestBodies() throws Exception {
        CivicPulseService service = service(new InMemoryDeltaStore());
        int port = availablePort();
        Javalin app = CivicPulseRoutes.create(service, null, port, 128, 300_000,
                "127.0.0.1", "test-key", false);
        try {
            assertEquals(401, request(port, "/ingest", "POST", "{}", null).statusCode());
            assertEquals(401, request(port, "/deltas?node=Bridge-A", "GET", null, "wrong").statusCode());
            assertEquals(400, request(port, "/ingest", "POST", "{bad", "test-key").statusCode());
            assertEquals(413, request(port, "/ingest", "POST", " ".repeat(129), "test-key").statusCode());
            assertEquals(400, request(port, "/ingest", "POST",
                    "{\"mac\":\"AA\",\"vibrationRms\":-1}", "test-key").statusCode());
            assertEquals(200, request(port, "/ingest", "POST",
                    "{\"nodeType\":\"bridge\",\"mac\":\"AA\",\"vibrationRms\":0.1}", "test-key").statusCode());
            assertEquals(200, request(port, "/deltas?node=Bridge-A", "GET", null, "test-key").statusCode());
            HttpResponse<String> health = request(port, "/health", "GET", null, null);
            assertEquals(200, health.statusCode());
            assertEquals("UP", JSON.readTree(health.body()).get("status").asText());
        } finally {
            app.stop();
            service.close();
        }
    }

    @Test
    void disabledSimulatorRejectsInjectionAndDeltaLimitIsClamped() throws Exception {
        InMemoryDeltaStore store = new InMemoryDeltaStore(200);
        for (int i = 0; i < 120; i++) store.append(new DeltaRecord("Bridge-A", "vibrationRms", i, i, i));
        CivicPulseService service = service(store);
        int port = availablePort();
        Javalin app = CivicPulseRoutes.create(service, null, port, 8192, 300_000,
                "127.0.0.1", null, false);
        try {
            assertEquals(409, request(port, "/sim/inject", "POST",
                    "{\"node\":\"Bridge-A\",\"type\":\"DROPOUT\",\"durationSec\":10}", null).statusCode());
            HttpResponse<String> response = request(port, "/deltas?node=Bridge-A&limit=999", "GET", null, null);
            assertEquals(200, response.statusCode());
            assertEquals(100, JSON.readTree(response.body()).size());
        } finally {
            app.stop();
            service.close();
        }
    }

    private static CivicPulseService service(InMemoryDeltaStore store) {
        return new CivicPulseService(new Anonymizer("route-test-salt"),
                new DiffEngine(2, 25, 1, 0), store,
                new AlertService(null, "unused", 50, new TemplateAlertFallback()));
    }

    private static HttpResponse<String> request(int port, String path, String method,
                                                String body, String apiKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(2));
        if (apiKey != null) request.header("X-API-Key", apiKey);
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        } else request.GET();
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
}