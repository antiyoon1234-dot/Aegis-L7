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
public class OffensiveDefenseListener implements Listener {

    private final SecurityManager securityManager;
    private final Plugin plugin;

    // 허니팟 가짜 엔티티 UUID 추적 (스레드 세이프)
    private final Set<UUID> honeypotEntities = java.util.concurrent.ConcurrentHashMap.newKeySet();
    
    // 매크로 감지용 플레이어별 클릭 타임스탬프 (최근 15번)
    private final Map<UUID, Queue<Long>> clickHistory = new java.util.concurrent.ConcurrentHashMap<>();

    public OffensiveDefenseListener(SecurityManager securityManager, Plugin plugin) {
        this.securityManager = securityManager;
        this.plugin = plugin;
        
        // 5초마다 일부 유저에게 무작위로 투명 허니팟 테스트 진행
        Bukkit.getScheduler().runTaskTimer(plugin, this::spawnRandomHoneypots, 100L, 100L);
    }

    /* -----------------------------------------------------
     * 1. HONEYPOT (투명 NPC 킬아우라 함정)
     * ----------------------------------------------------- */
    
    private void spawnRandomHoneypots() {
        if (!securityManager.getModuleManager().isEnabled(DefenseModule.HONEYPOT)) return;
        
        for (Player p : Bukkit.getOnlinePlayers()) {
            // 20% 확률로 킬아우라 테스트 (너무 자주 소환하면 서버 부하)
            if (Math.random() < 0.2) {
                spawnHoneypot(p);
            }
        }
    }

    private void spawnHoneypot(Player target) {
        // 플레이어 시야 뒤쪽(또는 위쪽)에 보이지 않는 박쥐를 1틱 소환
        Location loc = target.getLocation().add(0, 3, 0); 
        
        target.getWorld().spawn(loc, Bat.class, bat -> {
            bat.setInvisible(true);
            bat.setInvulnerable(false); // 맞을 수 있어야 함
            bat.setSilent(true);
            bat.setAwake(false);
            bat.setGravity(false);
            bat.setCustomName("L7_HONEYPOT");
            bat.setCustomNameVisible(false);
            
            honeypotEntities.add(bat.getUniqueId());
            
            // 1틱(0.05초) 뒤에 바로 삭제 (인간은 절대 반응/클릭 불가능, 킬아우라는 즉시 타격)
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (bat.isValid()) bat.remove();
                honeypotEntities.remove(bat.getUniqueId());
            }, 2L);
        });
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!securityManager.getModuleManager().isEnabled(DefenseModule.HONEYPOT)) return;

        if (honeypotEntities.contains(event.getEntity().getUniqueId())) {
            event.setCancelled(true); // 데미지 무시
            if (event.getDamager() instanceof Player damager) {
                // 인간이 0.1초만에 시야에도 없는 투명 박쥐를 때림 -> 100% 킬아우라(Combat Bot)
                securityManager.handleViolation(damager, DefenseModule.HONEYPOT, "투명 허니팟 타격 (Killaura/CombatBot 감지)");
            }
        }
    }

    /* -----------------------------------------------------
     * 2. MACRO CHECK (오토클리커 주파수 분석)
     * ----------------------------------------------------- */

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!securityManager.getModuleManager().isEnabled(DefenseModule.MACRO_CHECK)) return;
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        Queue<Long> clicks = clickHistory.computeIfAbsent(uuid, k -> new java.util.concurrent.ConcurrentLinkedQueue<>());
        clicks.add(System.currentTimeMillis());
        
        if (clicks.size() > 15) {
            clicks.poll(); // 최근 15개 유지
            analyzeMacro(player, new ArrayList<>(clicks));
        }
    }

    private void analyzeMacro(Player player, List<Long> times) {
        if (times.size() < 15) return;

        List<Long> deltas = new ArrayList<>();
        for (int i = 1; i < times.size(); i++) {
            deltas.add(times.get(i) - times.get(i - 1));
        }

        // 평균 클릭 간격 계산
        double sum = 0;
        for (long d : deltas) sum += d;
        double mean = sum / deltas.size();

        // 분산(Variance) 및 표준편차(Standard Deviation) 계산
        double varianceSum = 0;
        for (long d : deltas) {
            varianceSum += Math.pow(d - mean, 2);
        }
        double variance = varianceSum / deltas.size();
        double stdDev = Math.sqrt(variance);

        // 사람이 마우스를 클릭하면 표준편차가 보통 10~30 이상 발생함.
        // 표준편차가 3.0 이하라면 정확한 ms 간격으로 클릭하는 기계/매크로/오토클리커임.
        if (stdDev < 3.0 && mean < 150) { // 클릭 속도도 빠르면서(150ms 미만) 오차가 거의 없는 경우
            securityManager.handleViolation(player, DefenseModule.MACRO_CHECK, 
                    String.format("오토클리커/매크로 감지 (표준편차: %.2fms)", stdDev));
            
            // 기록 초기화 (계속 밴 이벤트 발생하는 것 방지)
            clickHistory.get(player.getUniqueId()).clear();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clickHistory.remove(event.getPlayer().getUniqueId());
    }
}
