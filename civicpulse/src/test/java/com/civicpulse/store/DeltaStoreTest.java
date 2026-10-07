package com.civicpulse.store;

import com.civicpulse.model.DeltaRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeltaStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void memoryStoreReturnsNewestMatchingRecords() {
        DeltaStore store = new InMemoryDeltaStore(4);
        store.append(new DeltaRecord("Bridge-A", "vibrationRms", 40, 1, 120));
        store.append(new DeltaRecord("Streetlight-B", "rfPowerDb", 30, 1, 110));
        store.append(new DeltaRecord("Bridge-A", "vibrationRms", 45, 2, 100));

        assertEquals(List.of(
            new DeltaRecord("Bridge-A", "vibrationRms", 40, 1, 120),
            new DeltaRecord("Bridge-A", "vibrationRms", 45, 2, 100)),
                store.readRecent("Bridge-A", 2));
    }

    @Test
    void yggdrasilStorePersistsCompactDeltaRecords() throws Exception {
        DeltaRecord expected = new DeltaRecord("Bridge-A", "vibrationRms", 40.5, 3, 123456);
        String key = "00112233445566778899aabbccddeeff";
        DeltaStore store = new YggdrasilDeltaStore(tempDir.resolve("db").toString(), key);
        store.append(expected);
        assertEquals(List.of(expected), store.readRecent("Bridge-A", 5));
        store.close();

        DeltaStore reopened = new YggdrasilDeltaStore(tempDir.resolve("db").toString(), key);
        assertEquals(List.of(expected), reopened.readRecent("Bridge-A", 5));
        reopened.close();
    }

    @Test
    void yggdrasilCompositeBucketsHandleHashCollisionsAndRestartSequence() throws Exception {
        String firstNode = "Bridge-Collision-A";
        String secondNode = collidingNode(firstNode);
        String key = "00112233445566778899aabbccddeeff";
        String directory = tempDir.resolve("composite").toString();
        DeltaStore store = new YggdrasilDeltaStore(directory, key);
        for (int index = 0; index < 8; index++) {
            store.append(new DeltaRecord(firstNode, "vibrationRms", index, index, 200 - index));
            store.append(new DeltaRecord(secondNode, "tempC", index, index, 200 + index));
        }
        store.close();

        DeltaStore reopened = new YggdrasilDeltaStore(directory, key);
        reopened.append(new DeltaRecord(firstNode, "vibrationRms", 99, 99, 999));
        assertEquals(List.of(
            new DeltaRecord(firstNode, "vibrationRms", 5, 5, 195),
            new DeltaRecord(firstNode, "vibrationRms", 6, 6, 194),
            new DeltaRecord(firstNode, "vibrationRms", 7, 7, 193),
                new DeltaRecord(firstNode, "vibrationRms", 99, 99, 999)),
                reopened.readRecent(firstNode, 4));
        assertEquals(8, reopened.readRecent(secondNode, 20).size());
        reopened.close();
    }

    private static String collidingNode(String original) {
        int bucket = original.hashCode() & 2047;
        for (int index = 0; index < 100_000; index++) {
            String candidate = "Bridge-Collision-" + index;
            if (!candidate.equals(original) && (candidate.hashCode() & 2047) == bucket) return candidate;
        }
        throw new AssertionError("Could not construct deterministic composite bucket collision");
    }
}