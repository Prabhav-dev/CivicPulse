package com.civicpulse.store;

import com.civicpulse.model.DeltaRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

public final class InMemoryDeltaStore implements DeltaStore {
    private final Deque<DeltaRecord> records = new ArrayDeque<>();
    private final int maxRecords;

    public InMemoryDeltaStore(int maxRecords) {
        if (maxRecords < 1) throw new IllegalArgumentException("maxRecords must be positive");
        this.maxRecords = maxRecords;
    }

    public InMemoryDeltaStore() {
        this(10_000);
    }

    @Override
    public synchronized void append(DeltaRecord record) {
        records.addLast(record);
        while (records.size() > maxRecords) records.removeFirst();
    }

    @Override
    public synchronized List<DeltaRecord> readRecent(String nodeLabel, int count) {
        if (count <= 0) return List.of();
        List<DeltaRecord> matches = new ArrayList<>();
        for (var iterator = records.descendingIterator(); iterator.hasNext() && matches.size() < count;) {
            DeltaRecord record = iterator.next();
            if (record.nodeLabel().equals(nodeLabel)) matches.add(record);
        }
        Collections.reverse(matches);
        return List.copyOf(matches);
    }

    @Override
    public void close() {
    }
}