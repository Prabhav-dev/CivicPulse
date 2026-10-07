package com.civicpulse.alert;

public record Alert(String nodeLabel, String metric, double pctChange,
                    long ts, String text, String source, double absoluteChange,
                    String mode, String kind) {
    public Alert(String nodeLabel, String metric, double pctChange,
                 long ts, String text, String source) {
        this(nodeLabel, metric, pctChange, ts, text, source, 0, "percent", "SHIFT");
    }
}