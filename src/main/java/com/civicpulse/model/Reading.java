package com.civicpulse.model;

public record Reading(String nodeLabel, String nodeType, double vibrationRms,
                     double tempC, double rfPowerDb, long ts, long receivedTs) {
    public Reading(String nodeLabel, String nodeType, double vibrationRms,
                   double tempC, double rfPowerDb, long ts) {
        this(nodeLabel, nodeType, vibrationRms, tempC, rfPowerDb, ts, ts);
    }
}