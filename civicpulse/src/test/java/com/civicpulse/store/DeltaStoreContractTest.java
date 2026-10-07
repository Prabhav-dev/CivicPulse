package com.civicpulse.store;

import com.civicpulse.model.DeltaRecord;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeltaStoreContractTest {
    @TempDir
    Path tempDir;

    @TestFactory
    Stream<DynamicTest> storesShareAppendOrderingContract() {
        return Stream.of(
                DynamicTest.dynamicTest("memory store", () -> {
                    verifyAppendOrdering(new InMemoryDeltaStore());
                }),
                DynamicTest.dynamicTest("Yggdrasil store", () -> {
                    String directory = tempDir.resolve("contract-yggdrasil").toString();
                    String key = "00112233445566778899aabbccddeeff";
                    DeltaStore firstRun = new YggdrasilDeltaStore(directory, key);
                    verifyAppendOrdering(firstRun);
                    firstRun.close();

                    DeltaStore afterRestart = new YggdrasilDeltaStore(directory, key);
                    assertEquals(expectedTail(), afterRestart.readRecent("Bridge-A", 2));
                    afterRestart.close();
                }));
    }

    private static void verifyAppendOrdering(DeltaStore store) {
        store.append(new DeltaRecord("Bridge-A", "vibrationRms", 10, 0, 300));
        store.append(new DeltaRecord("Streetlight-B", "rfPowerDb", -4, 0, 250));
        store.append(new DeltaRecord("Bridge-A", "vibrationRms", 20, 1, 100));
        store.append(new DeltaRecord("Bridge-A", "vibrationRms", 30, 2, 200));

        assertEquals(expectedTail(), store.readRecent("Bridge-A", 2));
        assertEquals(List.of(), store.readRecent("Bridge-A", 0));
    }

    private static List<DeltaRecord> expectedTail() {
        return List.of(new DeltaRecord("Bridge-A", "vibrationRms", 20, 1, 100),
                new DeltaRecord("Bridge-A", "vibrationRms", 30, 2, 200));
    }
}