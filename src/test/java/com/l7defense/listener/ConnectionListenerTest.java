package com.l7defense.listener;

import com.l7defense.TestServer;
import com.l7defense.module.*;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectionListenerTest {
    private ConnectionListener listener(TestServer env, boolean names, boolean ping) {
        return new ConnectionListener(env.security, Logger.getAnonymousLogger(), names, false, Set.of(), Set.of(),
                ping, false, true, false, false, List.of(), false, false);
    }
    private AsyncPlayerPreLoginEvent login(UUID id) throws Exception {
        return new AsyncPlayerPreLoginEvent("Human", InetAddress.getByName("192.0.2.1"), id);
    }
    @Test void requestedRetryDoesNotConsumeRateBudgetButThirdConnectionDoes() throws Exception {
        try (var env = new TestServer()) {
            when(env.modules.isEnabled(DefenseModule.PING)).thenReturn(true);
            when(env.modules.getPenalty(DefenseModule.PING)).thenReturn(PenaltyAction.NOTIFY);
            var listener = listener(env, false, true);
            UUID id = UUID.randomUUID();
            var first = login(id);
            listener.onAsyncPreLogin(first);
            assertNotEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, first.getLoginResult());
            var second = login(id);
            listener.onAsyncPreLogin(second);
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, second.getLoginResult());
            var third = login(id);
            listener.onAsyncPreLogin(third);
            assertNotEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, third.getLoginResult());
        }
    }
    @Test void anotherPluginsDenialDoesNotLeaveCaptchaAssignment() throws Exception {
        try (var env = new TestServer()) {
            when(env.modules.isEnabled(DefenseModule.ENTROPY)).thenReturn(true);
            when(env.modules.getPenalty(DefenseModule.ENTROPY)).thenReturn(PenaltyAction.NOTIFY);
            when(env.modules.isEnabled(DefenseModule.MAP_CAPTCHA)).thenReturn(true);
            var listener = listener(env, true, false);
            UUID id = UUID.randomUUID();
            var event = new AsyncPlayerPreLoginEvent("Bot12345", InetAddress.getByName("192.0.2.1"), id);
            listener.onAsyncPreLogin(event);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, net.kyori.adventure.text.Component.text("Other plugin"));
            listener.onPreLoginComplete(event);
            assertNull(env.security.getCaptchas().get(id));
        }
    }
    @Test void onlyEnabledCaptchaIsAlwaysSelected() throws Exception {
        try (var env = new TestServer()) {
            when(env.modules.isEnabled(DefenseModule.ENTROPY)).thenReturn(true);
            when(env.modules.getPenalty(DefenseModule.ENTROPY)).thenReturn(PenaltyAction.NOTIFY);
            when(env.modules.isEnabled(DefenseModule.MAP_CAPTCHA)).thenReturn(true);
            var listener = listener(env, true, false);
            for (int i = 1; i <= 32; i++) {
                UUID id = UUID.randomUUID();
                var event = new AsyncPlayerPreLoginEvent("Bot12345", InetAddress.getByName("192.0.2." + i), id);
                listener.onAsyncPreLogin(event);
                listener.onPreLoginComplete(event);
                assertTrue(env.security.needsMapCaptcha(id));
            }
        }
    }
}
