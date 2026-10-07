package com.civicpulse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class AppConfig {
    private final Properties values = new Properties();

    public AppConfig() {
        try (InputStream input = AppConfig.class.getResourceAsStream("/application.properties")) {
            if (input != null) values.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load application.properties", e);
        }
    }

    public String get(String key, String fallback) {
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