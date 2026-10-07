package com.civicpulse.alert;

public record Alert(String nodeLabel, String metric, double pctChange,
                    long ts, String text, String source) {
}