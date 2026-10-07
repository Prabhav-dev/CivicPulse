package com.civicpulse.store;

import com.civicpulse.model.DeltaRecord;

import java.util.List;

public interface DeltaStore extends AutoCloseable {
    void append(DeltaRecord record);
    List<DeltaRecord> readRecent(String nodeLabel, int count);
    @Override
    void close();
}