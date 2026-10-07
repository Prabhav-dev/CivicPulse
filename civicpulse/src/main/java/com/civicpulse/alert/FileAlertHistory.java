package com.civicpulse.alert;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class FileAlertHistory implements AlertHistory {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ALERTS = 200;
    private final Path path;

    public FileAlertHistory(Path path) {
        this.path = path;
    }

    @Override
    public synchronized List<Alert> readRecent(int count) {
        if (!Files.exists(path) || count <= 0) return List.of();
        try {
            List<Alert> alerts = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) alerts.add(JSON.readValue(line, Alert.class));
            }
            int start = Math.max(0, alerts.size() - Math.min(count, MAX_ALERTS));
            return List.copyOf(alerts.subList(start, alerts.size()));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load persisted alerts", e);
        }
    }

    @Override
    public synchronized void append(Alert alert) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            List<Alert> alerts = new ArrayList<>(readRecent(MAX_ALERTS));
            alerts.add(alert);
            if (alerts.size() > MAX_ALERTS) alerts = new ArrayList<>(alerts.subList(alerts.size() - MAX_ALERTS, alerts.size()));
            List<String> lines = new ArrayList<>(alerts.size());
            for (Alert item : alerts) lines.add(JSON.writeValueAsString(item));
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist alert history", e);
        }
    }
}