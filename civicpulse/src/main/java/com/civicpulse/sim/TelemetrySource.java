package com.civicpulse.sim;

import java.util.Map;
import java.util.function.Consumer;

public interface TelemetrySource extends AutoCloseable {
    void start(Consumer<Map<String, Object>> sink);
    void inject(String nodeLabel, String type, int durationSec);
    @Override
    void close();
}