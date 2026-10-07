package Yggdrasil;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import Yggdrasil.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Validates ASCON-128 AEAD disk bit-flip detection and maps
 * output to STATUS_AUTH_TAMPER_ERROR inside RequestDispatcher.
 */
public class TamperSecurityTest {

    @Test
    void corruptedPageReturnsAuthenticationTamperStatus(@TempDir Path tempDir) throws Exception {
        File workDir = tempDir.toFile();
        File dbFile = new File(workDir, StorageManager.DATABASE);

        byte[] masterKey = new byte[16];
        StorageManager storageManager = new StorageManager(masterKey, workDir.toString());
        storageManager.boot();
        
        WalManager walManager = new WalManager(workDir.toString());
        walManager.open();
        
        BPlusTree indexTree = new BPlusTree(storageManager);
        RecordManager recordManager = new RecordManager(storageManager, indexTree, walManager);
        recordManager.redoRecovery();

        // 1. Persist valid payload
        recordManager.insert(100, "secure_payload".getBytes(StandardCharsets.UTF_8));

        // Flush and release locks to persist state to disk
        walManager.flush();
        walManager.close();
        storageManager.close();

        // 2. Introduce single byte bit-flip corruption on physical page 1
        try (RandomAccessFile raf = new RandomAccessFile(dbFile, "rw")) {
            long corruptOffset = Page.PAGE_SIZE + Page.OFFSET_CIPHERTEXT + 5;
            raf.seek(corruptOffset);
            byte originalByte = raf.readByte();
            raf.seek(corruptOffset);
            raf.writeByte(originalByte ^ 0xFF);
            raf.getFD().sync();
        }

        // 3. Restart engine and rebuild dispatcher components
        StorageManager corruptStorage = new StorageManager(masterKey, workDir.toString());
        corruptStorage.boot();
        
        WalManager corruptWal = new WalManager(workDir.toString());
        corruptWal.open();
        
        BPlusTree corruptTree = new BPlusTree(corruptStorage);
        RecordManager corruptRecordMgr = new RecordManager(corruptStorage, corruptTree, corruptWal);

        RequestDispatcher dispatcher = new RequestDispatcher(corruptRecordMgr, corruptTree, null);

        // 4. Dispatch AUDIT_CHECK request -> verify handle of PageAuthenticationException
        WireProtocol.Frame auditRequest = new WireProtocol.Frame(
                new WireProtocol.Header(WireProtocol.MAGIC_BYTE, WireProtocol.OP_AUDIT_CHECK, 5001L, 0),
                new byte[0]
        );

        ByteBuffer responseBuffer = dispatcher.dispatch(auditRequest);
        responseBuffer.position(1);
        byte responseOpCode = responseBuffer.get();
        
        responseBuffer.position(WireProtocol.HEADER_SIZE);
        byte actualStatusCode = (responseOpCode == WireProtocol.OP_RESPONSE_ERR) 
                ? responseBuffer.get() 
                : WireProtocol.STATUS_SUCCESS;

        assertEquals(WireProtocol.STATUS_AUTH_TAMPER_ERROR, actualStatusCode);

        corruptStorage.close();
        corruptWal.close();

    }
}