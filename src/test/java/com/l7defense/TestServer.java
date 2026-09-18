package com.l7defense;

import com.l7defense.manager.SecurityManager;
import com.l7defense.manager.PlayerSessions;
import com.l7defense.module.ModuleConfigManager;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Small deterministic scheduler: exercises production listeners without a running server. */
public final class TestServer implements AutoCloseable {
    private record Scheduled(long tick, long period, Runnable callback, AtomicBoolean cancelled) {}
    private final List<Scheduled> scheduled = new ArrayList<>();
    public final Server server = mock(Server.class);
    public final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    public final Plugin plugin = mock(Plugin.class);
    public final YamlConfiguration config = new YamlConfiguration();
    public final List<Player> online = new ArrayList<>();
    public final List<org.bukkit.map.MapView> maps = new ArrayList<>();
    public final SecurityManager security = new SecurityManager(1, 30, 15, 10, 10, 100, 10, 50, 60, 3, 0, 15);
    public final ModuleConfigManager modules = mock(ModuleConfigManager.class);
    public final PlayerSessions sessions;
    private final Server previous;
    private long tick;

    public TestServer() throws Exception {
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        previous = (Server) field.get(null);
        field.set(null, server);
        Thread main = Thread.currentThread();
        when(server.isPrimaryThread()).thenAnswer(call -> Thread.currentThread() == main);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getOnlinePlayers()).thenAnswer(call -> online);
        when(server.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(server.createProfile(any(UUID.class), anyString())).thenAnswer(call -> {
            var profile = mock(com.destroystokyo.paper.profile.PlayerProfile.class);
            when(profile.getId()).thenReturn(call.getArgument(0));
            when(profile.getName()).thenReturn(call.getArgument(1));
            return profile;
        });
        when(server.getPlayer(any(UUID.class))).thenAnswer(call -> online.stream().filter(p -> p.getUniqueId().equals(call.getArgument(0))).findFirst().orElse(null));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("L7DefensePlugin");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var factory = mock(org.bukkit.inventory.ItemFactory.class);
        when(server.getItemFactory()).thenReturn(factory);
        when(factory.isApplicable(any(org.bukkit.inventory.meta.ItemMeta.class), any(Material.class))).thenReturn(true);
        when(factory.asMetaFor(any(org.bukkit.inventory.meta.ItemMeta.class), any(Material.class))).thenAnswer(call -> call.getArgument(0));
        when(factory.updateMaterial(any(org.bukkit.inventory.meta.ItemMeta.class), any(Material.class))).thenAnswer(call -> call.getArgument(1));
        when(factory.getItemMeta(any(Material.class))).thenAnswer(call -> {
            var meta = mock(org.bukkit.inventory.meta.MapMeta.class);
            when(meta.clone()).thenReturn(meta);
            var data = mock(org.bukkit.persistence.PersistentDataContainer.class);
            Map<NamespacedKey, String> values = new HashMap<>();
            doAnswer(c -> { values.put(c.getArgument(0), c.getArgument(2)); return null; }).when(data).set(any(NamespacedKey.class), eq(org.bukkit.persistence.PersistentDataType.STRING), anyString());
            when(data.get(any(NamespacedKey.class), eq(org.bukkit.persistence.PersistentDataType.STRING))).thenAnswer(c -> values.get(c.getArgument(0)));
            when(meta.getPersistentDataContainer()).thenReturn(data);
            return meta;
        });
        when(server.createInventory(isNull(), anyInt(), any(net.kyori.adventure.text.Component.class))).thenAnswer(call -> inventory(call.getArgument(1), false));
        when(server.createMap(any(World.class))).thenAnswer(call -> {
            var map = mock(org.bukkit.map.MapView.class);
            List<org.bukkit.map.MapRenderer> renderers = new ArrayList<>();
            when(map.getRenderers()).thenAnswer(c -> new ArrayList<>(renderers));
            doAnswer(c -> { renderers.add(c.getArgument(0)); return null; }).when(map).addRenderer(any());
            when(map.removeRenderer(any())).thenAnswer(c -> renderers.remove(c.getArgument(0)));
            when(map.getId()).thenReturn(maps.size());
            maps.add(map);
            return map;
        });
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(call -> schedule(call.getArgument(1), call.getArgument(2), call.getArgument(3)));
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenAnswer(call -> schedule(call.getArgument(1), call.getArgument(2), 0));
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> schedule(call.getArgument(1), 1, 0));
        sessions = new PlayerSessions(plugin);
        security.setSessions(sessions);
        security.setModuleManager(modules);
    }

    public Player player(UUID id) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.getName()).thenReturn("Human");
        when(p.getAddress()).thenReturn(new InetSocketAddress("192.0.2.1", 25565));
        when(p.isOnline()).thenReturn(true);
        when(p.getWorld()).thenReturn(mock(World.class));
        var playerInventory = (org.bukkit.inventory.PlayerInventory) inventory(41, true);
        when(p.getInventory()).thenReturn(playerInventory);
        var view = mock(org.bukkit.inventory.InventoryView.class);
        var top = new java.util.concurrent.atomic.AtomicReference<org.bukkit.inventory.Inventory>();
        when(view.getTopInventory()).thenAnswer(call -> top.get());
        when(p.getOpenInventory()).thenReturn(view);
        when(p.openInventory(any(org.bukkit.inventory.Inventory.class))).thenAnswer(call -> { top.set(call.getArgument(0)); return view; });
        online.add(p);
        sessions.onJoin(new org.bukkit.event.player.PlayerJoinEvent(p, net.kyori.adventure.text.Component.empty()));
        return p;
    }

    private org.bukkit.inventory.Inventory inventory(int size, boolean player) {
        var inventory = player ? mock(org.bukkit.inventory.PlayerInventory.class) : mock(org.bukkit.inventory.Inventory.class);
        org.bukkit.inventory.ItemStack[] items = new org.bukkit.inventory.ItemStack[size];
        when(inventory.getSize()).thenReturn(size);
        when(inventory.getItem(anyInt())).thenAnswer(call -> items[(int) call.getArgument(0)]);
        doAnswer(call -> { items[(int) call.getArgument(0)] = call.getArgument(1); return null; }).when(inventory).setItem(anyInt(), nullable(org.bukkit.inventory.ItemStack.class));
        if (player) {
            var playerInventory = (org.bukkit.inventory.PlayerInventory) inventory;
            var held = new java.util.concurrent.atomic.AtomicInteger();
            when(playerInventory.getHeldItemSlot()).thenAnswer(call -> held.get());
            doAnswer(call -> { held.set(call.getArgument(0)); return null; }).when(playerInventory).setHeldItemSlot(anyInt());
        }
        return inventory;
    }

    private BukkitTask schedule(Runnable callback, long delay, long period) {
        AtomicBoolean cancelled = new AtomicBoolean();
        BukkitTask task = mock(BukkitTask.class);
        doAnswer(call -> { cancelled.set(true); return null; }).when(task).cancel();
        when(task.isCancelled()).thenAnswer(call -> cancelled.get());
        scheduled.add(new Scheduled(tick + Math.max(1, delay), period, callback, cancelled));
        return task;
    }

    public void advance(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tick++;
            List<Scheduled> ready = scheduled.stream().filter(task -> task.tick <= tick).toList();
            scheduled.removeAll(ready);
            for (Scheduled task : ready) {
                if (task.cancelled.get()) continue;
                task.callback.run();
                if (task.period > 0 && !task.cancelled.get()) scheduled.add(new Scheduled(tick + task.period, task.period, task.callback, task.cancelled));
            }
            scheduled.removeIf(task -> task.cancelled.get());
        }
    }

    public int pendingTasks() { return (int) scheduled.stream().filter(task -> !task.cancelled.get()).count(); }
    @Override public void close() throws Exception {
        sessions.close();
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, previous);
    }
}
