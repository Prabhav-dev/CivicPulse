package com.civicpulse.model;

public record Reading(String nodeLabel, String nodeType, double vibrationRms,
                     double tempC, double rfPowerDb, long ts) {
}