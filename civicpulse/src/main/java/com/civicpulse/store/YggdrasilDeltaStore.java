package com.civicpulse.store;

import Yggdrasil.core.BPlusTree;
import Yggdrasil.core.RecordManager;
import Yggdrasil.core.StorageManager;
import Yggdrasil.core.WalManager;
import com.civicpulse.model.DeltaRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class YggdrasilDeltaStore implements DeltaStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BUCKET_BITS = 11;
    private static final int SEQUENCE_BITS = Integer.SIZE - 1 - BUCKET_BITS;
    private static final int BUCKET_COUNT = 1 << BUCKET_BITS;
    private static final int SEQUENCE_LIMIT = 1 << SEQUENCE_BITS;
    private static final int SEQUENCE_MASK = SEQUENCE_LIMIT - 1;

    private final StorageManager storage;
    private final WalManager wal;
    private final BPlusTree index;
    private final RecordManager records;
    private final Path sequenceFile;
    private final Map<Integer, Integer> nextSequence = new HashMap<>();

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
        sequenceFile = Path.of(directory, "civicpulse-sequences.properties");
        boolean booted = false;
        try {
            storage.boot();
            booted = true;
            wal.open();
            index = new BPlusTree(storage);
            records = new RecordManager(storage, index, wal);
            records.redoRecovery();
            loadSequences();
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
            int bucket = bucket(record.nodeLabel());
            int sequence = nextSequence.getOrDefault(bucket, 0);
            if (sequence >= SEQUENCE_LIMIT) {
                throw new IllegalStateException("Yggdrasil composite-key sequence exhausted for bucket " + bucket);
            }
            nextSequence.put(bucket, sequence + 1);
            try {
                persistSequences();
            } catch (IOException e) {
                if (sequence == 0) nextSequence.remove(bucket);
                else nextSequence.put(bucket, sequence);
                throw e;
            }
            int key = (bucket << SEQUENCE_BITS) | sequence;
            records.insert(key, encoded);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist anonymized delta", e);
        }
    }

    @Override
    public synchronized List<DeltaRecord> readRecent(String nodeLabel, int count) {
        if (count <= 0) return List.of();
        try {
            List<DeltaRecord> matches = new ArrayList<>();
            int bucket = bucket(nodeLabel);
            int lower = bucket << SEQUENCE_BITS;
            int upper = lower | SEQUENCE_MASK;
            for (BPlusTree.Entry entry : index.scanRangeEntries(lower, upper)) {
                byte[] bytes = records.find(entry.key());
                if (bytes == null) continue;
                DeltaRecord item = JSON.readValue(bytes, DeltaRecord.class);
                if (item.nodeLabel().equals(nodeLabel)) matches.add(item);
            }
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

    private void loadSequences() throws IOException {
        if (!Files.exists(sequenceFile)) {
            if (index.getRootID() != -1) {
                throw new IOException("Existing Yggdrasil index has no CivicPulse sequence catalog; migrate or use a fresh directory");
            }
            return;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(sequenceFile)) {
            properties.load(input);
        }
        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith("bucket.")) continue;
            int bucket = Integer.parseInt(name.substring("bucket.".length()));
            int sequence = Integer.parseInt(properties.getProperty(name));
            if (bucket < 0 || bucket >= BUCKET_COUNT || sequence < 0 || sequence > SEQUENCE_LIMIT) {
                throw new IOException("Invalid Yggdrasil sequence catalog entry: " + name);
            }
            nextSequence.put(bucket, sequence);
        }
    }

    private void persistSequences() throws IOException {
        Properties properties = new Properties();
        nextSequence.forEach((bucket, sequence) -> properties.setProperty("bucket." + bucket,
                Integer.toString(sequence)));
        Path temporary = sequenceFile.resolveSibling(sequenceFile.getFileName() + ".tmp");
        Files.createDirectories(sequenceFile.toAbsolutePath().getParent());
        try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, "CivicPulse Yggdrasil composite key high-water marks");
        }
        try {
            Files.move(temporary, sequenceFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, sequenceFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int bucket(String nodeLabel) {
        return nodeLabel.hashCode() & (BUCKET_COUNT - 1);
    }
}