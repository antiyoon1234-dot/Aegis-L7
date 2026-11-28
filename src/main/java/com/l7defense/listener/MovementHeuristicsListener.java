package com.l7defense.listener;
import com.l7defense.util.IpUtils;

import com.l7defense.manager.SecurityManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 마우스 시점 및 이동 휴리스틱 분석 (Anti-Baritone / Bot Movement AI).
 * <p>
 * 봇들은 목표를 향해 인간이 불가능한 속도(1틱 만에 정확히 180도)로 시점을 꺾거나(Snap Aim),
 * Pitch(위아래 고개 각도)가 완벽한 정수(예: 0.0, 90.0)로 고정된 채 움직이는 경우가 많습니다.
 */
public final class MovementHeuristicsListener implements Listener, AutoCloseable {

    private final SecurityManager securityManager;
    private final boolean enabled;
    private final com.github.benmanes.caffeine.cache.Cache<java.util.UUID, Integer> evidence =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().expireAfterWrite(5, java.util.concurrent.TimeUnit.SECONDS).maximumSize(10_000).build();
    private final com.github.benmanes.caffeine.cache.Cache<java.util.UUID, Long> graceUntil =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().expireAfterWrite(10, java.util.concurrent.TimeUnit.SECONDS).maximumSize(10_000).build();

    public MovementHeuristicsListener(SecurityManager securityManager, boolean enabled) {
        this.securityManager = securityManager;
        this.enabled = enabled;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (event.getPlayer() != null) {
            graceUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 3000L);
            evidence.invalidate(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        if (event.getPlayer() != null) {
            graceUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 3000L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        boolean isMoveEnabled = securityManager.getModuleManager() != null ? securityManager.getModuleManager().isEnabled(com.l7defense.module.DefenseModule.MOVEMENT) : enabled;
        if (!isMoveEnabled) return;
        if (event instanceof org.bukkit.event.player.PlayerTeleportEvent || event.getTo() == null) return;

        Player player = event.getPlayer();
        if (player == null || player.hasPermission("l7defense.admin")) return;
        if (player.isInsideVehicle() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) return;

        Long grace = graceUntil.getIfPresent(player.getUniqueId());
        if (grace != null && System.currentTimeMillis() < grace) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from == null || from.getWorld() != to.getWorld() || !Float.isFinite(to.getYaw()) || !Float.isFinite(to.getPitch())) return;

        // 시점 이동이 있는 경우
        if (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) {
            float deltaYaw = yawDifference(from.getYaw(), to.getYaw());
            float deltaPitch = Math.abs(to.getPitch() - from.getPitch());

            // 휴리스틱 1: 스냅 에임(Snap Aim) 방어
            // 1틱(0.05초)만에 Pitch 변동 없이 시점을 정확히 180도 꺾는 경우
            if (deltaYaw > 175.0f && deltaYaw < 185.0f && deltaPitch == 0.0f && from.getPitch() == to.getPitch()) {
                flagBot(player, "기계적인 시점 이동 감지 (Snap Aim)", 5);
                return;
            }

            // 휴리스틱 2: 완벽한 0.0 Pitch 고정 상태로 고속 이동+급회전 반복
            // 주의: 바닐라 클라이언트는 하늘(-90.0f)/바닥(90.0f)을 끝까지 볼 때 Pitch를 정확히 ±90.0f로 클램핑하므로 제외합니다.
            float p = to.getPitch();
            boolean movedHorizontally = from.getX() != to.getX() || from.getZ() != to.getZ();
            if (p == 0.0f && from.getPitch() == 0.0f && deltaYaw > 25.0f && movedHorizontally) {
                flagBot(player, "기계적인 시점 이동 감지 (수평 0° Pitch 고정 회전)", 10);
                return;
            }
        }
    }

    private void flagBot(Player player, String reason, int requiredCount) {
        int count = evidence.asMap().merge(player.getUniqueId(), 1, Integer::sum);
        if (count < requiredCount) return;
        evidence.invalidate(player.getUniqueId());
        // 정상 마우스 이동도 같은 각도를 만들 수 있으므로 반복 신호는 캡챠/주의로 확인합니다.
        securityManager.challengeHeuristic(player, com.l7defense.module.DefenseModule.MOVEMENT, reason);
    }
    static float yawDifference(float from, float to) {
        return Math.abs((float) Math.IEEEremainder((double) to - from, 360.0));
    }

    @EventHandler public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        evidence.invalidate(event.getPlayer().getUniqueId());
        graceUntil.invalidate(event.getPlayer().getUniqueId());
    }

    @Override
    public void close() {
        evidence.invalidateAll();
        graceUntil.invalidateAll();
    }
}
