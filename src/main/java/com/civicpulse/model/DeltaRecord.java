package com.civicpulse.model;

public record DeltaRecord(String nodeLabel, String metric, double pctChange,
                          int position, long ts, String kind) {
    public DeltaRecord(String nodeLabel, String metric, double pctChange, int position, long ts) {
        this(nodeLabel, metric, pctChange, position, ts, "SHIFT");
    }

    public DeltaRecord {
        if (kind == null || kind.isBlank()) kind = "SHIFT";
    }
}