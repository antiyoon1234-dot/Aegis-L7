package com.l7defense.manager;

import com.l7defense.util.IpUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** 메인 스레드에서 만든 접속 스냅샷과 플레이어별 크기가 제한된 비동기 작업함. */
public final class PlayerSessions implements Listener, AutoCloseable {
    public record Snapshot(UUID id, String ip, UUID connectionId, boolean admin) {
        public Snapshot(UUID id, String ip, UUID connectionId) {
            this(id, ip, connectionId, false);
        }
    }
    private record ActionKey(Snapshot session, String kind) {}
    private record Action(Player player, Runnable task) {}
    private final ConcurrentHashMap<Player, Snapshot> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ActionKey, Action> mailbox = new ConcurrentHashMap<>();
    private final Plugin plugin;
    private final BukkitTask drainTask;
    private volatile boolean closed;

    public PlayerSessions(Plugin plugin) {
        this.plugin = plugin;
        drainTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drain, 1L, 1L);
        for (Player player : Bukkit.getOnlinePlayers()) register(player);
    }

    public Snapshot get(Player player) {
        if (player == null || closed) return null;
        return sessions.get(player);
    }

    private void register(Player player) {
        if (player == null || closed) return;
        boolean isAdmin = false;
        try {
            isAdmin = player.isOp() || player.hasPermission("l7defense.admin");
        } catch (Throwable ignored) {}
        sessions.put(player, new Snapshot(player.getUniqueId(), IpUtils.getIp(player), UUID.randomUUID(), isAdmin));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) { register(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Snapshot old = sessions.remove(event.getPlayer());
        if (old != null) mailbox.keySet().removeIf(key -> old.equals(key.session));
    }

    // kind는 플러그인이 정한 고정 문자열만 사용하며 사용자 입력을 키로 사용하지 않습니다.
    public void submit(Player player, String kind, Runnable task) {
        if (player == null || task == null || closed) return;
        Snapshot session = get(player);
        if (session == null) return;
        ActionKey key = new ActionKey(session, kind);
        mailbox.putIfAbsent(key, new Action(player, task));
        if (closed || get(player) != session) mailbox.remove(key);
    }

    private void drain() {
        if (closed) return;
        int remaining = 512;
        for (var entry : mailbox.entrySet()) {
            if (remaining-- == 0) break;
            Action action = mailbox.remove(entry.getKey());
            if (action == null || get(action.player) != entry.getKey().session || !action.player.isOnline()) continue;
            try { action.task.run(); }
            catch (Throwable ex) { plugin.getLogger().log(Level.WARNING, "[L7] 플레이어 작업 실패", ex); }
        }
    }

    @Override public void close() {
        closed = true;
        drainTask.cancel();
        mailbox.clear();
        sessions.clear();
    }
}
