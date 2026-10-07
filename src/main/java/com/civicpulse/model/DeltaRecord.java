package com.civicpulse.model;

public record DeltaRecord(String nodeLabel, String metric, double pctChange,
                          int position, long ts, String kind,
                          double absoluteChange, String mode) {
    public DeltaRecord(String nodeLabel, String metric, double pctChange, int position, long ts) {
        this(nodeLabel, metric, pctChange, position, ts, "SHIFT", 0, "percent");
    }

    public DeltaRecord(String nodeLabel, String metric, double pctChange,
                       int position, long ts, String kind) {
        this(nodeLabel, metric, pctChange, position, ts, kind, 0, "percent");
    }

    public DeltaRecord {
        if (kind == null || kind.isBlank()) kind = "SHIFT";
        if (mode == null || mode.isBlank()) mode = "percent";
    }
}