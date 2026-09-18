package com.l7defense.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 메인 스레드 전용. 재접속/재발급/종료 시 이전 세션의 타이머를 취소합니다. */
public final class PlayerTasks implements AutoCloseable {
    private final Plugin plugin;
    private final Map<UUID, Map<String, BukkitTask>> tasks = new HashMap<>();
    public PlayerTasks(Plugin plugin) { this.plugin = plugin; }
    public void later(Player player, String kind, long delay, Runnable action) {
        UUID id = player.getUniqueId();
        var perPlayer = tasks.computeIfAbsent(id, key -> new HashMap<>());
        BukkitTask previous = perPlayer.remove(kind);
        if (previous != null) previous.cancel();
        perPlayer.put(kind, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            perPlayer.remove(kind);
            if (perPlayer.isEmpty()) tasks.remove(id, perPlayer);
            if (player.isOnline()) action.run();
        }, delay));
    }
    public void cancel(UUID id) {
        var old = tasks.remove(id);
        if (old != null) old.values().forEach(BukkitTask::cancel);
    }
    @Override public void close() {
        tasks.values().forEach(map -> map.values().forEach(BukkitTask::cancel));
        tasks.clear();
    }
}
