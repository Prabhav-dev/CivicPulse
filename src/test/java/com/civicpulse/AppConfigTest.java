package com.civicpulse;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppConfigTest {
    @Test
    void secretsUseEnvironmentThenSystemPropertyThenProperties() {
        Properties defaults = new Properties();
        defaults.setProperty("anonymizer.salt", "file-value");
        String previous = System.getProperty("anonymizer.salt");
        System.setProperty("anonymizer.salt", "system-value");
        try {
            AppConfig withEnvironment = new AppConfig(defaults,
                    Map.of("CIVICPULSE_ANONYMIZER_SALT", "environment-value"));
            assertEquals("environment-value", withEnvironment.get("anonymizer.salt", "fallback"));

            AppConfig withoutEnvironment = new AppConfig(defaults, Map.of());
            assertEquals("system-value", withoutEnvironment.get("anonymizer.salt", "fallback"));

            System.clearProperty("anonymizer.salt");
            assertEquals("file-value", withoutEnvironment.get("anonymizer.salt", "fallback"));
        } finally {
            if (previous == null) System.clearProperty("anonymizer.salt");
            else System.setProperty("anonymizer.salt", previous);
        }
    }
}