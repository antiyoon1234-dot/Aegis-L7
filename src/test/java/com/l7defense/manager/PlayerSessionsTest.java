package com.l7defense.manager;

import com.l7defense.TestServer;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PlayerSessionsTest {
    @Test void floodsCoalesceAndStaleSessionCannotTouchReconnect() throws Exception {
        try (var env = new TestServer()) {
            UUID id = UUID.randomUUID();
            var old = env.player(id);
            AtomicInteger count = new AtomicInteger();
            for (int i = 0; i < 10000; i++) env.sessions.submit(old, "feedback", count::incrementAndGet);
            env.advance(1);
            assertEquals(1, count.get());
            env.sessions.submit(old, "feedback", count::incrementAndGet);
            env.sessions.onQuit(new org.bukkit.event.player.PlayerQuitEvent(old, net.kyori.adventure.text.Component.empty()));
            var fresh = env.player(id);
            env.sessions.submit(fresh, "feedback", count::incrementAndGet);
            env.advance(1);
            assertEquals(2, count.get());
        }
    }
}
