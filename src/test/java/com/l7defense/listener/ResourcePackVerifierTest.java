package com.l7defense.listener;

import com.l7defense.TestServer;
import com.l7defense.module.DefenseModule;
import net.kyori.adventure.text.Component;
import org.bukkit.event.player.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResourcePackVerifierTest {
    @Test void unrelatedPackCannotCompleteChallengeAndDownloadFailureDoesNotBan() throws Exception {
        try (var env = new TestServer(); var listener = new ResourcePackVerifier(env.plugin, env.security, true)) {
            var player = env.player(UUID.randomUUID());
            env.security.getCaptchas().start(player.getUniqueId(), DefenseModule.RP_CAPTCHA);
            listener.onPlayerJoin(new PlayerJoinEvent(player, Component.empty()));
            ArgumentCaptor<UUID> request = ArgumentCaptor.forClass(UUID.class);
            ArgumentCaptor<byte[]> digest = ArgumentCaptor.forClass(byte[].class);
            verify(player).setResourcePack(request.capture(), anyString(), digest.capture(), any(Component.class), eq(true));
            try (var pack = getClass().getResourceAsStream("/captcha/verification-v1.zip")) {
                assertNotNull(pack);
                assertArrayEquals(java.security.MessageDigest.getInstance("SHA-1").digest(pack.readAllBytes()), digest.getValue());
            }
            listener.onResourcePackStatus(new PlayerResourcePackStatusEvent(player, UUID.randomUUID(), PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED));
            assertTrue(env.security.needsResourcePackCaptcha(player.getUniqueId()));
            listener.onResourcePackStatus(new PlayerResourcePackStatusEvent(player, request.getValue(), PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD));
            assertFalse(env.security.isBlocked("192.0.2.1"));
            assertTrue(env.security.needsResourcePackCaptcha(player.getUniqueId()));
            verify(player).kick(any(Component.class));
        }
    }

    @Test void acceptedStatusKeepsTimeoutAndQuitCancelsIt() throws Exception {
        try (var env = new TestServer(); var listener = new ResourcePackVerifier(env.plugin, env.security, true)) {
            var player = env.player(UUID.randomUUID());
            env.security.getCaptchas().start(player.getUniqueId(), DefenseModule.RP_CAPTCHA);
            listener.onPlayerJoin(new PlayerJoinEvent(player, Component.empty()));
            ArgumentCaptor<UUID> request = ArgumentCaptor.forClass(UUID.class);
            verify(player).setResourcePack(request.capture(), anyString(), any(byte[].class), any(Component.class), eq(true));
            listener.onResourcePackStatus(new PlayerResourcePackStatusEvent(player, request.getValue(), PlayerResourcePackStatusEvent.Status.ACCEPTED));
            assertEquals(2, env.pendingTasks());
            listener.onPlayerQuit(new PlayerQuitEvent(player, Component.empty()));
            assertEquals(1, env.pendingTasks());
            env.advance(1200);
            verify(player, never()).kick(any(Component.class));
        }
    }
}
