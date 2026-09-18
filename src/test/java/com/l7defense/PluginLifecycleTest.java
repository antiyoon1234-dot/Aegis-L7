package com.l7defense;

import com.l7defense.listener.MapCaptchaListener;
import com.l7defense.module.DefenseModule;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginLifecycleTest {
    private void set(Object target, String field, Object value) throws Exception {
        var declared = L7DefensePlugin.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    @Test void disablingPluginRestoresItemsAndReleasesLiveResources() throws Exception {
        try (var env = new TestServer()) {
            var plugin = mock(L7DefensePlugin.class, CALLS_REAL_METHODS);
            doReturn(env.server).when(plugin).getServer();
            doReturn(java.util.logging.Logger.getAnonymousLogger()).when(plugin).getLogger();
            when(env.server.getMessenger()).thenReturn(mock(org.bukkit.plugin.messaging.Messenger.class));
            var player = env.player(UUID.randomUUID());
            player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 2));
            env.security.getCaptchas().start(player.getUniqueId(), DefenseModule.MAP_CAPTCHA);
            var maps = new MapCaptchaListener(env.plugin, env.security);
            maps.triggerMapCaptcha(player);
            set(plugin, "securityManager", env.security);
            set(plugin, "lifecycles", new ArrayList<AutoCloseable>(List.of(env.sessions, maps)));
            plugin.onDisable();
            assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
            assertEquals(2, player.getInventory().getItem(0).getAmount());
            assertTrue(env.maps.get(0).getRenderers().isEmpty());
            assertEquals(0, env.pendingTasks());
            assertNull(env.sessions.get(player));
            assertFalse(env.security.isActive());
        }
    }

    @Test void invalidReloadKeepsExistingProtectionActive() throws Exception {
        try (var env = new TestServer()) {
            var plugin = mock(L7DefensePlugin.class, CALLS_REAL_METHODS);
            doReturn(env.config).when(plugin).getConfig();
            doReturn(java.util.logging.Logger.getAnonymousLogger()).when(plugin).getLogger();
            doNothing().when(plugin).reloadConfig();
            set(plugin, "securityManager", env.security);
            env.config.set("rate-limit.max-connections", -1);
            assertFalse(plugin.reloadPlugin());
            assertTrue(env.security.isActive());
        }
    }
}
