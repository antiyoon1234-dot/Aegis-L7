package com.l7defense.listener;

import com.l7defense.manager.SecurityManager;
import com.l7defense.module.DefenseModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * v1.8 Offensive Defense & Anti-Cheat (공격형 방어 및 안티치트)
 * 1. 투명 NPC 허니팟: 킬아우라(Combat Bot) 강제 적발
 * 2. 매크로 주파수 분석기: 오토클리커 기계적 클릭 간격 감지
 */
public class OffensiveDefenseListener implements Listener, AutoCloseable {

    private final SecurityManager securityManager;
    private final Plugin plugin;
    private final org.bukkit.scheduler.BukkitTask spawnTask;

    // 허니팟 가짜 엔티티 UUID 추적 (스레드 세이프)
    private final Set<UUID> honeypotEntities = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // 최근 전투에 참여한 플레이어 타임스탬프 (전투 중인 유저에게만 허니팟 배치)
    private final Map<UUID, Long> lastCombatTime = new java.util.concurrent.ConcurrentHashMap<>();

    // 매크로 감지용 플레이어별 클릭 타임스탬프 (최근 25번)
    private final Map<UUID, Deque<Long>> clickHistory = new java.util.concurrent.ConcurrentHashMap<>();

    // 매크로 알림 쿨다운 (30초)
    private final Map<UUID, Long> macroCooldown = new java.util.concurrent.ConcurrentHashMap<>();

    public OffensiveDefenseListener(SecurityManager securityManager, Plugin plugin) {
        this.securityManager = securityManager;
        this.plugin = plugin;

        // 5초마다 전투 중인 일부 유저에게 무작위로 투명 허니팟 테스트 진행
        spawnTask = Bukkit.getScheduler().runTaskTimer(plugin, this::spawnRandomHoneypots, 100L, 100L);
    }

    /* -----------------------------------------------------
     * 1. HONEYPOT (투명 NPC 킬아우라 함정)
     * ----------------------------------------------------- */

    private void spawnRandomHoneypots() {
        if (securityManager.getModuleManager() == null || !securityManager.getModuleManager().isEnabled(DefenseModule.HONEYPOT)) return;

        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("l7defense.admin") || p.isDead() || !p.isValid()) continue;
            // 최근 5초 내 전투(엔티티 타격) 기록이 있는 유저만 검사하여 평화로운 채광/건축 유저 방해 방지
            Long lastCombat = lastCombatTime.get(p.getUniqueId());
            if (lastCombat == null || now - lastCombat > 5000L) continue;
            if (Math.random() < 0.25) {
                spawnHoneypot(p);
            }
        }
    }

    private void spawnHoneypot(Player target) {
        if (!target.isOnline() || target.isDead() || target.getWorld() == null) return;
        // 플레이어 등 뒤(시야 정반대 2.5블록 뒤)의 빈 공간에만 소환하여 벽/천장 끼임 및 정상 타격 오탐 방지
        Vector backDir = target.getLocation().getDirection().setY(0);
        if (backDir.lengthSquared() < 1e-4) return;
        backDir.normalize().multiply(-2.5);
        Location loc = target.getLocation().add(backDir).add(0, 1.5, 0);

        if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;
        if (!loc.getBlock().getType().isAir() || !loc.clone().add(0, -1, 0).getBlock().getType().isAir()) return;

        target.getWorld().spawn(loc, Bat.class, bat -> {
            bat.setPersistent(false);
            bat.setInvisible(true);
            bat.setInvulnerable(false);
            bat.setSilent(true);
            bat.setAwake(false);
            bat.setGravity(false);
            bat.setAI(false);
            bat.setCollidable(false);
            bat.setCustomName("L7_HONEYPOT");
            bat.setCustomNameVisible(false);

            honeypotEntities.add(bat.getUniqueId());

            // 2틱(0.1초) 뒤에 바로 삭제
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (bat.isValid()) bat.remove();
                honeypotEntities.remove(bat.getUniqueId());
            }, 2L);
        });
    }

    @EventHandler
    public void onAnyEntityDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (honeypotEntities.contains(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player damager) {
            if (honeypotEntities.contains(event.getEntity().getUniqueId())) {
                event.setCancelled(true);
                if (securityManager.getModuleManager() == null || !securityManager.getModuleManager().isEnabled(DefenseModule.HONEYPOT)) return;
                // 등 뒤에 있는 허니팟을 실제로 보지 않고 타격했는지 각도 검증 (내적 < 0.25)
                Vector look = damager.getEyeLocation().getDirection().normalize();
                Vector toTarget = event.getEntity().getLocation().toVector().subtract(damager.getEyeLocation().toVector());
                if (toTarget.lengthSquared() > 0.01 && look.dot(toTarget.normalize()) < 0.25) {
                    securityManager.handleViolation(damager, DefenseModule.HONEYPOT, "등 뒤 투명 허니팟 타격 (Killaura/CombatBot 감지)");
                }
                return;
            }
            if (!damager.hasPermission("l7defense.admin")) {
                lastCombatTime.put(damager.getUniqueId(), System.currentTimeMillis());
            }
        }
    }

    /* -----------------------------------------------------
     * 2. MACRO CHECK (오토클리커 주파수 분석)
     * ----------------------------------------------------- */

    @EventHandler(ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (securityManager.getModuleManager() == null || !securityManager.getModuleManager().isEnabled(DefenseModule.MACRO_CHECK)) return;
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        // LEFT_CLICK_BLOCK은 블록 채굴 홀드 시 틱 단위(50ms)로 반복 발생하므로 공중 클릭(전투/클릭 스팸) 위주로 분석
        if (event.getAction() != Action.LEFT_CLICK_AIR) return;

        Player player = event.getPlayer();
        if (player == null || player.hasPermission("l7defense.admin")) return;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long cooldown = macroCooldown.get(uuid);
        if (cooldown != null && now < cooldown) return;

        Deque<Long> clicks = clickHistory.computeIfAbsent(uuid, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        synchronized (clicks) {
            // 2초 이상 지난 클릭 기록 제거
            while (!clicks.isEmpty() && now - clicks.peekFirst() > 2000L) {
                clicks.pollFirst();
            }
            clicks.addLast(now);
            while (clicks.size() > 25) {
                clicks.pollFirst();
            }

            // 1초 내 24 CPS 초과는 인간 물리 한계 초과 오토클리커로 판정
            long oneSecAgo = now - 1000L;
            int recentCps = 0;
            for (Long ts : clicks) {
                if (ts >= oneSecAgo) recentCps++;
            }
            if (recentCps >= 24) {
                clicks.clear();
                macroCooldown.put(uuid, now + 30_000L);
                securityManager.handleViolation(player, DefenseModule.MACRO_CHECK, "비정상적 초고속 클릭 감지 (" + recentCps + " CPS)");
                return;
            }

            if (clicks.size() >= 20) {
                analyzeMacro(player, new ArrayList<>(clicks), now);
            }
        }
    }

    private void analyzeMacro(Player player, List<Long> times, long now) {
        if (times.size() < 20) return;

        List<Long> deltas = new ArrayList<>();
        for (int i = 1; i < times.size(); i++) {
            long d = times.get(i) - times.get(i - 1);
            // 동일 틱 배치 처리(0ms)는 제외
            if (d > 0) deltas.add(d);
        }
        if (deltas.size() < 15) return;

        double sum = 0;
        for (long d : deltas) sum += d;
        double mean = sum / deltas.size();

        // 마인크래프트 서버는 50ms 틱 경계로 패킷을 처리하므로 정확히 50ms/100ms 배수 근처의 틱 양자화 오탐 방지
        boolean nearTickMultiple = Math.abs(mean - 50.0) < 6.0 || Math.abs(mean - 100.0) < 6.0;
        if (nearTickMultiple) return;

        double varianceSum = 0;
        for (long d : deltas) {
            varianceSum += Math.pow(d - mean, 2);
        }
        double stdDev = Math.sqrt(varianceSum / deltas.size());

        // 20회 이상 연속 고속 클릭(평균 85ms 미만, 틱 배수 제외)이면서 오차가 극도로 일정한 매크로만 적발
        if (stdDev < 2.0 && mean < 85.0 && mean > 20.0) {
            String reason = String.format(Locale.ROOT, "오토클리커/매크로 감지 (평균: %.1fms, 표준편차: %.2fms)", mean, stdDev);
            macroCooldown.put(player.getUniqueId(), now + 30_000L);
            clicksClear(player.getUniqueId());
            securityManager.handleViolation(player, DefenseModule.MACRO_CHECK, reason);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        clickHistory.remove(id);
        lastCombatTime.remove(id);
        macroCooldown.remove(id);
    }

    private void clicksClear(UUID id) {
        Deque<Long> clicks = clickHistory.get(id);
        if (clicks != null) clicks.clear();
    }

    @Override public void close() {
        spawnTask.cancel();
        for (UUID id : honeypotEntities) {
            var entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
        honeypotEntities.clear();
        clickHistory.clear();
        lastCombatTime.clear();
        macroCooldown.clear();
    }
}
