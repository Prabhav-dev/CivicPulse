package com.civicpulse.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlertHistoryTest {
    @TempDir
    Path directory;

    @Test
    void fileHistoryReloadsLatestAlertsAfterRestart() {
        Path path = directory.resolve("alerts.jsonl");
        Alert first = new Alert("Bridge-A", "vibrationRms", 30, 100,
                "Possible sensor issue. Verify the reading.", "TEMPLATE");
        Alert second = new Alert("Streetlight-B", "rfPowerDb", -7, 200,
                "Possible interference. Inspect the antenna.", "TEMPLATE");

        FileAlertHistory beforeRestart = new FileAlertHistory(path);
        beforeRestart.append(first);
        beforeRestart.append(second);
        beforeRestart.close();

        FileAlertHistory afterRestart = new FileAlertHistory(path);
        assertEquals(List.of(first, second), afterRestart.readRecent(10));
    }
}