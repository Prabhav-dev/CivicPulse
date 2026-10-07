package com.civicpulse;

import com.civicpulse.alert.Alert;
import com.civicpulse.alert.AlertService;
import com.civicpulse.anonymize.Anonymizer;
import com.civicpulse.diff.DiffEngine;
import com.civicpulse.model.DeltaRecord;
import com.civicpulse.model.Reading;
import com.civicpulse.store.DeltaStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

public final class CivicPulseService {
    private final Anonymizer anonymizer;
    private final DiffEngine diffEngine;
    private final DeltaStore store;
    private final AlertService alertService;
    private final Deque<Alert> alerts = new ArrayDeque<>();

    public CivicPulseService(Anonymizer anonymizer, DiffEngine diffEngine,
                             DeltaStore store, AlertService alertService) {
        this.anonymizer = anonymizer;
        this.diffEngine = diffEngine;
        this.store = store;
        this.alertService = alertService;
    }

    public synchronized List<DeltaRecord> ingest(Map<String, Object> raw) {
        Reading reading = anonymizer.anonymize(raw);
        List<DeltaRecord> deltas = diffEngine.accept(reading);
        for (DeltaRecord delta : deltas) {
            store.append(delta);
            Alert alert = alertService.create(delta, store.readRecent(delta.nodeLabel(), 5));
            alerts.addLast(alert);
            while (alerts.size() > 200) alerts.removeFirst();
        }
        return deltas;
    }

    public synchronized List<Alert> recentAlerts(int limit) {
        int boundedLimit = Math.max(0, Math.min(limit, alerts.size()));
        List<Alert> result = new ArrayList<>(alerts);
        return List.copyOf(result.subList(result.size() - boundedLimit, result.size()));
    }

    public Map<String, DiffEngine.NodeSnapshot> status() {
        return diffEngine.snapshot();
    }

    public List<DeltaRecord> readRecent(String nodeLabel, int count) {
        return store.readRecent(nodeLabel, count);
    }

    public void close() {
        alertService.close();
        store.close();
    }
}