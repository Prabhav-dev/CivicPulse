package com.civicpulse.alert;

import java.util.List;

public interface AlertHistory extends AutoCloseable {
    List<Alert> readRecent(int count);
    void append(Alert alert);
    @Override
    default void close() { }
}