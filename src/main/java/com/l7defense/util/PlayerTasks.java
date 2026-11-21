package com.l7defense.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 스레드 안전 타이머 관리자. 재접속/재발급/종료 시 이전 세션의 타이머를 취소합니다. */
public final class PlayerTasks implements AutoCloseable {
    private final Plugin plugin;
    private final Map<UUID, Map<String, BukkitTask>> tasks = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public PlayerTasks(Plugin plugin) { this.plugin = plugin; }

    public synchronized void later(Player player, String kind, long delay, Runnable action) {
        if (player == null || action == null || closed) return;
        UUID id = player.getUniqueId();
        var perPlayer = tasks.computeIfAbsent(id, key -> new ConcurrentHashMap<>());
        BukkitTask previous = perPlayer.remove(kind);
        if (previous != null) previous.cancel();
        try {
            perPlayer.put(kind, Bukkit.getScheduler().runTaskLater(plugin, () -> {
                perPlayer.remove(kind);
                if (perPlayer.isEmpty()) tasks.remove(id, perPlayer);
                if (!closed && player.isOnline()) action.run();
            }, delay));
        } catch (Throwable ignored) {}
    }

    public synchronized void cancel(UUID id) {
        if (id == null) return;
        var old = tasks.remove(id);
        if (old != null) old.values().forEach(BukkitTask::cancel);
    }

    @Override
    public synchronized void close() {
        closed = true;
        tasks.values().forEach(map -> map.values().forEach(BukkitTask::cancel));
        tasks.clear();
    }
}
