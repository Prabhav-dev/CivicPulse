package com.civicpulse.alert;

import com.civicpulse.model.DeltaRecord;

import java.util.List;

@FunctionalInterface
public interface AlertGenerator extends AutoCloseable {
    Alert create(DeltaRecord latest, List<DeltaRecord> recent);

    @Override
    default void close() { }
}