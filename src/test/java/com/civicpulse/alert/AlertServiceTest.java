package com.civicpulse.alert;

import com.civicpulse.model.DeltaRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlertServiceTest {
    @Test
    void missingKeyUsesTemplateFallback() {
        try (AlertService service = new AlertService(null, "gemini-3.8-flash", 5000, new TemplateAlertFallback())) {
            Alert alert = service.create(new DeltaRecord("Bridge-A", "vibrationRms", 40, 2, 1000), List.of());
            assertEquals("TEMPLATE", alert.source());
            assertEquals("Bridge-A", alert.nodeLabel());
            org.junit.jupiter.api.Assertions.assertTrue(alert.text().contains("40.0% increase in vibrationRms"));
        }
    }
}