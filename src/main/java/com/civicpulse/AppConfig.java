package com.civicpulse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

public final class AppConfig {
    private final Properties values = new Properties();
    private final Map<String, String> environment;

    public AppConfig() {
        this.environment = System.getenv();
        try (InputStream input = AppConfig.class.getResourceAsStream("/application.properties")) {
            if (input != null) values.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load application.properties", e);
        }
    }

    AppConfig(Properties defaults, Map<String, String> environment) {
        this.values.putAll(defaults);
        this.environment = Map.copyOf(environment);
    }

    public String get(String key, String fallback) {
        String envName = switch (key) {
            case "anonymizer.salt" -> "CIVICPULSE_ANONYMIZER_SALT";
            case "yggdrasil.key" -> "CIVICPULSE_YGGDRASIL_KEY";
            case "api.key" -> "CIVICPULSE_API_KEY";
            default -> null;
        };
        if (envName != null) {
            String envValue = environment.get(envName);
            if (envValue != null && !envValue.isBlank()) return envValue;
        }
        return System.getProperty(key, values.getProperty(key, fallback));
    }

    public int getInt(String key, int fallback) {
        return Integer.parseInt(get(key, Integer.toString(fallback)));
    }

    public long getLong(String key, long fallback) {
        return Long.parseLong(get(key, Long.toString(fallback)));
    }

    public double getDouble(String key, double fallback) {
        return Double.parseDouble(get(key, Double.toString(fallback)));
    }

    public boolean getBoolean(String key, boolean fallback) {
        return Boolean.parseBoolean(get(key, Boolean.toString(fallback)));
    }
}