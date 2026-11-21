package com.l7defense.listener;

import com.l7defense.manager.SecurityManager;
import com.l7defense.module.DefenseModule;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

/**
 * v1.9 조명 연산 폭탄 (Light Update Crasher) 차단
 * 발광석, 횃불 등 빛 업데이트를 유발하는 블록을 비정상적인 속도로 설치/파괴하는 핵(FastPlace/Nuker) 감지
 */
public class BlockCrasherListener implements Listener, AutoCloseable {

    private final SecurityManager securityManager;

    // 유저별 최근 블록 조작 타임스탬프 큐 (스레드 세이프)
    private record BlockAction(long time, boolean light) {}
    private final Map<UUID, Queue<BlockAction>> blockHistory = new java.util.concurrent.ConcurrentHashMap<>();

    // 조명(Light) 업데이트를 크게 유발하여 랙을 일으키기 쉬운 블록들
    private static final Set<Material> LIGHT_BLOCKS = EnumSet.of(
            Material.GLOWSTONE, Material.TORCH, Material.SOUL_TORCH, 
            Material.REDSTONE_LAMP, Material.LANTERN, Material.SOUL_LANTERN, 
            Material.LAVA, Material.SEA_LANTERN, Material.BEACON, Material.CAMPFIRE
    );

    public BlockCrasherListener(SecurityManager securityManager) {
        this.securityManager = securityManager;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        handleBlockAction(event.getPlayer(), event.getBlock().getType(), event);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        handleBlockAction(event.getPlayer(), event.getBlock().getType(), event);
    }

    private void handleBlockAction(Player player, Material blockType, org.bukkit.event.Cancellable event) {
        if (securityManager.getModuleManager() == null || !securityManager.getModuleManager().isEnabled(DefenseModule.LIGHT_CRASHER)) return;

        UUID uuid = player.getUniqueId();
        Queue<BlockAction> history = blockHistory.computeIfAbsent(uuid, k -> new java.util.ArrayDeque<>());
        
        long now = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        boolean isViolation = false;

        synchronized (history) {
            while (!history.isEmpty()) {
                BlockAction head = history.peek();
                if (head != null && now - head.time >= 1000) {
                    history.poll();
                } else {
                    break;
                }
            }
            history.add(new BlockAction(now, LIGHT_BLOCKS.contains(blockType)));

            // 조명 블록은 초당 12회 제한, 일반 블록은 초당 25회 제한 (기계적인 FastPlace 모드)
            int threshold = 25;
            long lightCount = 0;
            for (BlockAction action : history) {
                if (action.light()) lightCount++;
            }

            if (history.size() > threshold || lightCount > 12) {
                BlockAction oldest = history.peek();
                if (oldest != null && (now - oldest.time < 1000)) {
                    isViolation = true;
                    history.clear(); // 연속 밴 방지
                }
            }
        }

        if (isViolation) {
            event.setCancelled(true);
            securityManager.handleViolation(player, DefenseModule.LIGHT_CRASHER, "비정상적 블록 설치/파괴 속도 (FastPlace/Nuker 조명 랙 방어)");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        blockHistory.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void close() {
        blockHistory.clear();
    }
}
