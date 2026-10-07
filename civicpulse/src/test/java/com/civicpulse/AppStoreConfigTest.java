package com.civicpulse;

import com.civicpulse.store.DeltaStore;
import com.civicpulse.store.InMemoryDeltaStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppStoreConfigTest {
    @Test
    void badYggdrasilKeyFailsStartupByDefault() {
        withProperties(() -> {
            System.setProperty("store.type", "yggdrasil");
            System.setProperty("yggdrasil.key", "bad-key");
            System.clearProperty("store.fallback-to-memory");

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> App.createStore(new AppConfig()));
            assertTrue(failure.getMessage().contains("Yggdrasil initialization failed"));
            assertTrue(failure.getMessage().contains("32 hexadecimal characters"));
        });
    }

    @Test
    void memoryFallbackRequiresExplicitOptIn() {
        withProperties(() -> {
            System.setProperty("store.type", "yggdrasil");
            System.setProperty("yggdrasil.key", "bad-key");
            System.setProperty("store.fallback-to-memory", "true");

            DeltaStore store = App.createStore(new AppConfig());
            assertInstanceOf(InMemoryDeltaStore.class, store);
            store.close();
        });
    }

    private static void withProperties(Runnable test) {
        String storeType = System.getProperty("store.type");
        String yggdrasilKey = System.getProperty("yggdrasil.key");
        String fallback = System.getProperty("store.fallback-to-memory");
        try { test.run(); }
        finally {
            restore("store.type", storeType);
            restore("yggdrasil.key", yggdrasilKey);
            restore("store.fallback-to-memory", fallback);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}