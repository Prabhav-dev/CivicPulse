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

    @Test
    void resolvesPortAndHostForCloudEnvironments() {
        Properties defaults = new Properties();
        defaults.setProperty("server.port", "7070");
        defaults.setProperty("server.host", "127.0.0.1");

        // When PORT is provided (as in Render), server.port uses it and server.host defaults to 0.0.0.0
        AppConfig renderConfig = new AppConfig(defaults, Map.of("PORT", "10000"));
        assertEquals("10000", renderConfig.get("server.port", "7070"));
        assertEquals("0.0.0.0", renderConfig.get("server.host", "127.0.0.1"));

        // When HOST is explicitly provided, it takes precedence
        AppConfig explicitHostConfig = new AppConfig(defaults, Map.of("PORT", "10000", "HOST", "192.168.1.5"));
        assertEquals("192.168.1.5", explicitHostConfig.get("server.host", "127.0.0.1"));

        // When no cloud env is present, defaults from properties are preserved
        AppConfig localConfig = new AppConfig(defaults, Map.of());
        assertEquals("7070", localConfig.get("server.port", "7070"));
        assertEquals("127.0.0.1", localConfig.get("server.host", "127.0.0.1"));
    }
}