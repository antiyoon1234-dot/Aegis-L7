package com.l7defense.listener;

import com.l7defense.TestServer;
import com.l7defense.module.DefenseModule;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapCanvas;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CaptchaRegressionTest {
    @Test void greenPaneInPersonalInventoryCannotPassGui() throws Exception {
        try (var env = new TestServer(); var gui = new CaptchaListener(env.plugin, env.security)) {
            var player = env.player(UUID.randomUUID());
            env.security.getCaptchas().start(player.getUniqueId(), DefenseModule.GUI_CAPTCHA);
            gui.openCaptcha(player);
            var event = mock(InventoryClickEvent.class);
            when(event.getWhoClicked()).thenReturn(player);
            var view = player.getOpenInventory();
            when(event.getView()).thenReturn(view);
            when(event.getRawSlot()).thenReturn(30);
            when(event.getCurrentItem()).thenReturn(new ItemStack(Material.GREEN_STAINED_GLASS_PANE));
            gui.onInventoryClick(event);
            assertTrue(env.security.needsGuiCaptcha(player.getUniqueId()));
            verify(event).setCancelled(true);
            var inventory = player.getOpenInventory().getTopInventory();
            int correct = -1;
            for (int i = 0; i < 27; i++) if (inventory.getItem(i).getType() == Material.GREEN_STAINED_GLASS_PANE) correct = i;
            assertTrue(correct >= 0);
            when(event.getRawSlot()).thenReturn(correct);
            var answerItem = inventory.getItem(correct);
            when(event.getCurrentItem()).thenReturn(answerItem);
            gui.onInventoryClick(event);
            assertFalse(env.security.needsGuiCaptcha(player.getUniqueId()));
            verify(player, never()).closeInventory();
            env.advance(1);
            verify(player).closeInventory();
        }
    }

    @Test void asyncMapAnswerRestoresOriginalSlotAndReleasesRenderer() throws Exception {
        try (var env = new TestServer(); var maps = new MapCaptchaListener(env.plugin, env.security)) {
            var player = env.player(UUID.randomUUID());
            var original = new ItemStack(Material.DIAMOND, 3);
            var other = new ItemStack(Material.EMERALD, 7);
            player.getInventory().setItem(0, original);
            player.getInventory().setItem(2, other);
            player.getInventory().setHeldItemSlot(2);
            env.security.getCaptchas().start(player.getUniqueId(), DefenseModule.MAP_CAPTCHA);
            maps.triggerMapCaptcha(player);
            var map = env.maps.get(0);
            var canvas = mock(MapCanvas.class);
            map.getRenderers().get(0).render(map, canvas, player);
            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(canvas).drawText(eq(20), eq(50), any(), text.capture());
            String answer = text.getValue().substring("CODE: ".length());
            clearInvocations(player);
            var event = new AsyncPlayerChatEvent(true, player, answer, Set.of());
            CompletableFuture.runAsync(() -> maps.onChat(event)).join();
            assertTrue(event.isCancelled());
            verifyNoInteractions(player); // async path only uses the pre-captured session
            env.advance(1);
            assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
            assertEquals(3, player.getInventory().getItem(0).getAmount());
            assertSame(other, player.getInventory().getItem(2));
            assertEquals(2, player.getInventory().getHeldItemSlot());
            assertTrue(map.getRenderers().isEmpty());
            assertFalse(env.security.needsMapCaptcha(player.getUniqueId()));
            assertEquals(1, env.pendingTasks());
        }
    }

    @Test void replacingCaptchaAndQuitCancelOldTimeouts() throws Exception {
        try (var env = new TestServer()) {
            var player = env.player(UUID.randomUUID());
            var tasks = new com.l7defense.util.PlayerTasks(env.plugin);
            var count = new java.util.concurrent.atomic.AtomicInteger();
            tasks.later(player, "captcha", 10, count::incrementAndGet);
            tasks.later(player, "captcha", 20, count::incrementAndGet);
            env.advance(10);
            assertEquals(0, count.get());
            tasks.cancel(player.getUniqueId());
            env.advance(20);
            assertEquals(0, count.get());
        }
    }

    @Test void captchaFeedbackNeverAccessesPlayerAsynchronously() throws Exception {
        try (var env = new TestServer(); var gui = new CaptchaListener(env.plugin, env.security)) {
            var player = env.player(UUID.randomUUID());
            env.security.getCaptchas().start(player.getUniqueId(), DefenseModule.GUI_CAPTCHA);
            clearInvocations(player);
            var event = new AsyncPlayerChatEvent(true, player, "hello", Set.of());
            CompletableFuture.runAsync(() -> gui.onPlayerChat(event)).join();
            assertTrue(event.isCancelled());
            verifyNoInteractions(player);
            env.advance(1);
            verify(player).sendMessage(any(Component.class));
        }
    }

    @Test void infrastructureRetryIsOneUseAndAccountBound() throws Exception {
        try (var env = new TestServer()) {
            var player = env.player(UUID.randomUUID());
            UUID id = player.getUniqueId();
            assertFalse(env.security.recordAndCheckConnection("192.0.2.1", id));
            env.security.offerCaptchaRetry(player);
            assertFalse(env.security.recordAndCheckConnection("192.0.2.1", id));
            env.security.offerCaptchaRetry(player);
            assertTrue(env.security.recordAndCheckConnection("192.0.2.1", id));
        }
    }
}
