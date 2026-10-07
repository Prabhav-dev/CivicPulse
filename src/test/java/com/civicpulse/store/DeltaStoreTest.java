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
        store.append(new DeltaRecord("Bridge-A", "vibrationRms", 40, 1, 100));
        store.append(new DeltaRecord("Streetlight-B", "rfPowerDb", 30, 1, 110));
        store.append(new DeltaRecord("Bridge-A", "vibrationRms", 45, 2, 120));

        assertEquals(List.of(
                new DeltaRecord("Bridge-A", "vibrationRms", 40, 1, 100),
                new DeltaRecord("Bridge-A", "vibrationRms", 45, 2, 120)),
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
}