package Yggdrasil;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import Yggdrasil.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression suite covering edge cases in WAL parsing, channel reading,
 * torn-write truncations, and LSN sequence tracking.
 */
public final class WalManagerRegressionTest {

    @Test
    void readsFragmentedChannelFully() throws Exception {
        ReadableByteChannel fragmentedChannel = new ReadableByteChannel() {
            private final byte[] dataSource = {1, 2, 3, 4, 5};
            private int readIndex = 0;

            @Override
            public int read(ByteBuffer destination) {
                if (readIndex == dataSource.length) return -1;
                int chunkSize = Math.min(1 + (readIndex % 2), Math.min(destination.remaining(), dataSource.length - readIndex));
                destination.put(dataSource, readIndex, chunkSize);
                readIndex += chunkSize;
                return chunkSize;
            }

            @Override
            public boolean isOpen() { return true; }

            @Override
            public void close() { }
        };

        ByteBuffer readBuffer = ByteBuffer.allocate(5);
        assertTrue(WalManager.readFullyFrom(fragmentedChannel, readBuffer));
    }

    @Test
    void discardsTornTailOnOpen(@TempDir Path tempDir) throws Exception {
        File targetWalFile = tempDir.resolve("engine.wal").toFile();

        // Ensure clean slate without relying on non-deterministic System.gc() call
        if (targetWalFile.exists()) {
            try (RandomAccessFile raf = new RandomAccessFile(targetWalFile, "rw")) {
                raf.setLength(0);
            }
        }

        WalManager wal = new WalManager(tempDir.toString());
        wal.open();
        wal.logTupleInsert(7, 0x00010001L, new byte[] {9, 8, 7, 6});
        wal.flush();
        wal.close();

        // Truncate file mid-record to simulate a torn write / un-flushed tail
        try (RandomAccessFile raf = new RandomAccessFile(targetWalFile, "rw")) {
            raf.setLength(raf.length() - 3);
        }

        WalManager recoveryManager = new WalManager(tempDir.toString());
        recoveryManager.open();

        assertEquals(0, targetWalFile.length(), "Torn WAL tail should be discarded on open");
        recoveryManager.close();
    }

    @Test
    void pageUpdateAdvancesLsn(@TempDir Path tempDir) throws Exception {
        File targetWalFile = tempDir.resolve("engine.wal").toFile();
        
        WalManager wal = new WalManager(tempDir.toString());
        wal.open();
        wal.truncateLog(); // Start with empty state

        long lsn1 = wal.logPageUpdate(3, new byte[] {1, 2});
        long lsn2 = wal.logTupleDelete(4, 5L);

        assertEquals(1, lsn1);
        assertEquals(2, lsn2);

        wal.close();

        assertTrue(targetWalFile.length() > 0, "WAL entry should be flushed to disk");

        if (!targetWalFile.delete()) {
            targetWalFile.deleteOnExit();
        }
    }
}