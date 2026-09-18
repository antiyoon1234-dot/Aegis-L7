package com.l7defense.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class FirewallManagerTest {
    @TempDir Path temp;
    private String probe(String mode) throws Exception {
        String binary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(FirewallProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return "\"" + binary + "\" -cp \"" + classes + "\" " + FirewallProbe.class.getName() + " " + mode;
    }
    @Test void consumesLargeStdoutAndStderrWithoutDeadlock() throws Exception {
        try (var manager = new FirewallManager(null, Logger.getAnonymousLogger(), false, "", "")) {
            String command = probe("flood");
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> assertTrue(manager.execute(command)));
        }
    }
    @Test void hungProcessIsKilledAtDeadline() throws Exception {
        try (var manager = new FirewallManager(null, Logger.getAnonymousLogger(), false, "", "")) {
            String command = probe("sleep");
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> assertFalse(manager.execute(command)));
        }
    }
    @Test void expiryRemovesOsRuleAndDuplicateBlocksAreCoalesced() throws Exception {
        Path marker = temp.resolve("rules.txt");
        String prefix = probe("append") + " \"" + marker + "\" ";
        try (var manager = new FirewallManager(null, Logger.getAnonymousLogger(), true, prefix + "block", prefix + "unblock")) {
            for (int i = 0; i < 100; i++) manager.executeBlock("192.0.2.1", 700);
            assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                while (!Files.exists(marker) || Files.readAllLines(marker).size() < 2) Thread.sleep(20);
            });
            assertEquals(List.of("block", "unblock"), Files.readAllLines(marker));
        }
    }
    @Test void commandParserPreservesQuotedPathsAndRejectsBrokenQuotes() {
        assertEquals(List.of("C:/Program Files/tool", "-s", "192.0.2.1"), FirewallManager.splitCommand("\"C:/Program Files/tool\"  -s 192.0.2.1"));
        assertThrows(IllegalArgumentException.class, () -> FirewallManager.splitCommand("'unfinished"));
    }
}
