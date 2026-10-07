package Yggdrasil;

import Yggdrasil.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Diagnostic test harness verifying WAL crash recovery sequence,
 * tracking TupleIDs and page/slot allocations across engine reboots.
 */
public class WalCrashDebugTest {
    @Test
    void recoversRecordAfterRestart(@TempDir Path tempDir) throws Exception {
        String directory = tempDir.toString();
        byte[] key = new byte[16];
        StorageManager storagePhase1 = new StorageManager(key, directory);
        storagePhase1.boot();

        WalManager walPhase1 = new WalManager(directory);
        walPhase1.open();
        walPhase1.truncateLog();

        BPlusTree indexTree1 = new BPlusTree(storagePhase1);
        RecordManager recordMgr1 = new RecordManager(storagePhase1, indexTree1, walPhase1);

        int recordKey = 500;
        byte[] rawPayload = "Uncommitted Row 1".getBytes(StandardCharsets.UTF_8);
        recordMgr1.insert(recordKey, rawPayload);
        walPhase1.close();
        storagePhase1.close();

        StorageManager storagePhase2 = new StorageManager(key, directory);
        storagePhase2.boot();

        WalManager walPhase2 = new WalManager(directory);
        walPhase2.open();

        BPlusTree indexTree2 = new BPlusTree(storagePhase2);
        RecordManager recordMgr2 = new RecordManager(storagePhase2, indexTree2, walPhase2);
        recordMgr2.redoRecovery();
        byte[] recoveredPayload = recordMgr2.find(recordKey);
        assertNotNull(recoveredPayload);
        assertArrayEquals(rawPayload, recoveredPayload);

        walPhase2.close();
        storagePhase2.close();
    }
}