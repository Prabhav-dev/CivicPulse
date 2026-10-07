package com.civicpulse.store;

import Yggdrasil.core.BPlusTree;
import Yggdrasil.core.RecordManager;
import Yggdrasil.core.StorageManager;
import Yggdrasil.core.WalManager;
import com.civicpulse.model.DeltaRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class YggdrasilDeltaStore implements DeltaStore {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StorageManager storage;
    private final WalManager wal;
    private final RecordManager records;
    private int nextKey = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE / 2);

    public YggdrasilDeltaStore(String directory, String keyHex) throws IOException {
        byte[] key;
        try {
            key = java.util.HexFormat.of().parseHex(keyHex);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("yggdrasil.key must be 32 hexadecimal characters", e);
        }
        if (key.length != 16) throw new IllegalArgumentException("yggdrasil.key must encode exactly 16 bytes");

        storage = new StorageManager(key, directory);
        wal = new WalManager(directory);
        boolean booted = false;
        try {
            storage.boot();
            booted = true;
            wal.open();
            records = new RecordManager(storage, new BPlusTree(storage), wal);
            records.redoRecovery();
        } catch (IOException | RuntimeException e) {
            if (booted) {
                try { storage.close(); } catch (IOException closeFailure) { e.addSuppressed(closeFailure); }
            }
            try { wal.close(); } catch (IOException closeFailure) { e.addSuppressed(closeFailure); }
            throw e;
        }
    }

    @Override
    public synchronized void append(DeltaRecord record) {
        try {
            byte[] encoded = JSON.writeValueAsBytes(record);
            for (int attempts = 0; attempts < 1024; attempts++) {
                int key = nextKey++ & Integer.MAX_VALUE;
                if (key == 0 || records.containsKey(key)) continue;
                records.insert(key, encoded);
                return;
            }
            throw new IllegalStateException("Could not allocate a unique Yggdrasil index key");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist anonymized delta", e);
        }
    }

    @Override
    public synchronized List<DeltaRecord> readRecent(String nodeLabel, int count) {
        if (count <= 0) return List.of();
        try {
            List<DeltaRecord> matches = new ArrayList<>();
            for (byte[] bytes : records.scan(0, BPlusTree.Operator.GREATER_THAN)) {
                DeltaRecord item = JSON.readValue(bytes, DeltaRecord.class);
                if (item.nodeLabel().equals(nodeLabel)) matches.add(item);
            }
            matches.sort(Comparator.comparingLong(DeltaRecord::ts));
            int start = Math.max(0, matches.size() - count);
            return List.copyOf(matches.subList(start, matches.size()));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read anonymized deltas", e);
        }
    }

    @Override
    public synchronized void close() {
        IOException failure = null;
        try { storage.close(); } catch (IOException e) { failure = e; }
        try { wal.close(); } catch (IOException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) throw new IllegalStateException("Unable to close Yggdrasil store cleanly", failure);
    }
}