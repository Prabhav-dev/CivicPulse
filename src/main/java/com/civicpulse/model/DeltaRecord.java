package com.civicpulse.model;

public record DeltaRecord(String nodeLabel, String metric, double pctChange,
                          int position, long ts) {
}