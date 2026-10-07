package com.civicpulse.alert;

import com.civicpulse.model.DeltaRecord;

import java.util.Locale;

public final class TemplateAlertFallback {
    public Alert create(DeltaRecord record) {
        if ("DROPOUT".equals(record.kind())) {
            String text = String.format(Locale.ROOT,
                    "No data has arrived from %s for %s. Check the sensor connection and power, then verify the reporting path.",
                    record.nodeLabel(), record.metric());
                return new Alert(record.nodeLabel(), record.metric(), 0, record.ts(), text, "TEMPLATE",
                    0, "absolute", "DROPOUT");
        }
            if ("absolute".equals(record.mode())) {
                String unit = "tempC".equals(record.metric()) ? "°C" : "dB";
                String text = String.format(Locale.ROOT,
                    "%s recorded a %+.1f %s change in %s. Verify the sensor and inspect the related equipment.",
                    record.nodeLabel(), record.absoluteChange(), unit, record.metric());
                return new Alert(record.nodeLabel(), record.metric(), record.pctChange(), record.ts(), text,
                    "TEMPLATE", record.absoluteChange(), record.mode(), record.kind());
            }
        String direction = record.pctChange() < 0 ? "decrease" : "increase";
        String action = switch (record.metric()) {
            case "vibrationRms" -> "Inspect the structure and verify the vibration sensor is firmly mounted";
            case "tempC" -> "Check the local equipment and confirm the temperature sensor is operating correctly";
            case "rfPowerDb" -> "Inspect the radio path, antenna, and nearby interference sources";
            default -> "Inspect the sensor and verify its recent readings";
        };
        String text = String.format(Locale.ROOT,
                "%s recorded a %.1f%% %s in %s. %s.", record.nodeLabel(),
                Math.abs(record.pctChange()), direction, record.metric(), action);
        return new Alert(record.nodeLabel(), record.metric(), record.pctChange(), record.ts(), text, "TEMPLATE",
            record.absoluteChange(), record.mode(), record.kind());
    }
}