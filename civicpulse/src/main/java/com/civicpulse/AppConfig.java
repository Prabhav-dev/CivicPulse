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
        String envValue = resolveEnvironment(key);
        if (envValue != null && !envValue.isBlank()) return envValue;

        String sysProp = System.getProperty(key);
        if (sysProp != null && !sysProp.isBlank()) return sysProp;

        if ("server.host".equals(key) && (environment.containsKey("PORT") || environment.containsKey("RENDER"))) {
            return "0.0.0.0";
        }

        return values.getProperty(key, fallback);
    }

    private String resolveEnvironment(String key) {
        return switch (key) {
            case "server.port" -> firstNonBlank(environment.get("PORT"),
                    environment.get("CIVICPULSE_PORT"), environment.get("SERVER_PORT"));
            case "server.host" -> firstNonBlank(environment.get("HOST"),
                    environment.get("CIVICPULSE_HOST"), environment.get("SERVER_HOST"));
            case "anonymizer.salt" -> environment.get("CIVICPULSE_ANONYMIZER_SALT");
            case "yggdrasil.key" -> environment.get("CIVICPULSE_YGGDRASIL_KEY");
            case "api.key" -> environment.get("CIVICPULSE_API_KEY");
            case "gemini.api-key" -> firstNonBlank(environment.get("GEMINI_API_KEY"), environment.get("CIVICPULSE_GEMINI_API_KEY"));
            default -> {
                String normalized = "CIVICPULSE_" + key.replace('.', '_').replace('-', '_').toUpperCase();
                yield environment.get(normalized);
            }
        };
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) return candidate;
        }
        return null;
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