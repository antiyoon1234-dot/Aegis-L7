package com.l7defense.listener;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.l7defense.manager.SecurityManager;
import com.l7defense.module.DefenseModule;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 통계 및 휴리스틱 기반 엑스레이(X-Ray / SeedCracker) 탐지 리스너
 *
 * 1. 유저의 채굴 패턴에서 다이아몬드/고대잔해 등 희귀 광석과 일반 암석 채굴 비율을 분석합니다.
 * 2. 실크터치/광석 설치 후 재채굴 어뷰징(오탐)을 방지하기 위해 플레이어가 설치한 광석 위치를 캐싱합니다.
 * 3. 비정상적인 광석 비율(예: 25% 이상)이 탐지되면 관리자에게 좌표와 함께 경고/주의(NOTIFY)를 전송합니다.
 */
public class AntiXrayListener implements Listener, AutoCloseable {

    private final SecurityManager securityManager;

    // 플레이어가 직접 설치한 광석 캐시 (10분 만료, 최대 20,000개)
    private final Cache<String, Long> placedOres = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .maximumSize(20_000)
            .build();

    // 경고 쿨다운 캐시 (동일 플레이어에게 60초 내 중복 알림 방지)
    private final Cache<UUID, Long> alertCooldown = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(10_000)
            .build();

    // 희귀 광석 (X-Ray 주 표적)
    public static final Set<Material> RARE_ORES = EnumSet.of(
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.ANCIENT_DEBRIS,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE
    );

    // 일반 광석
    public static final Set<Material> COMMON_ORES = EnumSet.of(
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.NETHER_QUARTZ_ORE
    );

    // 채굴 기본 암석 및 지형 블록 (통계 분모)
    public static final Set<Material> EXCAVATION_BLOCKS = EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.COBBLESTONE, Material.COBBLED_DEEPSLATE,
            Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF,
            Material.NETHERRACK, Material.BLACKSTONE, Material.BASALT,
            Material.DIRT, Material.GRAVEL, Material.SAND, Material.RED_SAND,
            Material.CALCITE, Material.DRIPSTONE_BLOCK
    );

    public enum BlockCategory {
        RARE_ORE,
        COMMON_ORE,
        EXCAVATION
    }

    public record MinedBlock(long time, BlockCategory category) {}

    // 플레이어별 최근 5분 채굴 기록 (Thread-safe)
    private final Map<UUID, Queue<MinedBlock>> miningHistory = new ConcurrentHashMap<>();

    private com.l7defense.manager.PacketAntiXrayManager packetAntiXrayManager;

    // 기본 임계치 설정 (최소 채굴 수 12개, 최소 희귀광석 3개, 희귀광석 비율 20% 이상)
    private final int minTotalBlocks;
    private final int minRareOres;
    private final double rareOreRatioThreshold;

    public AntiXrayListener(SecurityManager securityManager) {
        this(securityManager, 12, 3, 0.20);
    }

    public AntiXrayListener(SecurityManager securityManager, int minTotalBlocks, int minRareOres, double rareOreRatioThreshold) {
        this.securityManager = securityManager;
        this.minTotalBlocks = minTotalBlocks;
        this.minRareOres = minRareOres;
        this.rareOreRatioThreshold = rareOreRatioThreshold;
    }

    public int getMinTotalBlocks() { return minTotalBlocks; }
    public int getMinRareOres() { return minRareOres; }
    public double getRareOreRatioThreshold() { return rareOreRatioThreshold; }

    public void setPacketAntiXrayManager(com.l7defense.manager.PacketAntiXrayManager packetAntiXrayManager) {
        this.packetAntiXrayManager = packetAntiXrayManager;
    }

    public com.l7defense.manager.PacketAntiXrayManager getPacketAntiXrayManager() {
        return packetAntiXrayManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Material type = event.getBlock().getType();
        if (RARE_ORES.contains(type)) {
            String key = getBlockKey(event.getBlock());
            placedOres.put(key, System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        if (securityManager.getModuleManager() == null || !securityManager.getModuleManager().isEnabled(DefenseModule.ANTI_XRAY)) {
            return;
        }

        Block block = event.getBlock();

        // 1. 패킷 허니팟(Decoy Trap) 검사: 가짜 다이아몬드 패킷을 투시하여 굴착한 경우 즉각 차단 및 경고
        if (packetAntiXrayManager != null && packetAntiXrayManager.isDecoy(player, block.getLocation())) {
            event.setCancelled(true);
            packetAntiXrayManager.removeDecoy(player, block.getLocation());
            String reason = String.format("X-Ray ESP 패킷 트랩 적발 [밀폐된 가짜 다이아몬드 패킷 투시 굴착, 위치: %s %d, %d, %d]",
                    block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
            securityManager.handleViolation(player, DefenseModule.ANTI_XRAY, reason);
            return;
        }

        // 2. 인접 마스킹 블록 복원 및 패킷 마스킹 갱신
        if (packetAntiXrayManager != null) {
            packetAntiXrayManager.onBlockAction(player, block);
        }

        String blockKey = getBlockKey(block);

        // 플레이어가 설치한 광석인 경우 캐시에서 제거하고 희귀 광석 집계에서 제외
        boolean wasPlaced = placedOres.getIfPresent(blockKey) != null;
        if (wasPlaced) {
            placedOres.invalidate(blockKey);
            return;
        }

        processMiningAction(player, block.getType(), block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public boolean processMiningAction(Player player, Material material, String worldName, int x, int y, int z) {
        BlockCategory category = categorize(material);
        if (category == null) {
            return false;
        }

        UUID uuid = player.getUniqueId();
        Queue<MinedBlock> history = miningHistory.computeIfAbsent(uuid, k -> new ArrayDeque<>());

        long now = System.currentTimeMillis();
        boolean isViolation = false;
        int rareCount = 0;
        int totalCount = 0;
        double ratio = 0.0;

        synchronized (history) {
            // 5분(300,000ms) 지난 오래된 기록 정리
            while (!history.isEmpty()) {
                MinedBlock head = history.peek();
                if (head != null && now - head.time() >= 300_000) {
                    history.poll();
                } else {
                    break;
                }
            }

            history.add(new MinedBlock(now, category));
            // 극단적인 블록 파괴 공격(Nuker 등) 시 큐 메모리 상한 제한
            while (history.size() > 500) {
                history.poll();
            }

            for (MinedBlock record : history) {
                if (record.category() == BlockCategory.RARE_ORE) {
                    rareCount++;
                }
                totalCount++;
            }

            if (totalCount >= minTotalBlocks && rareCount >= minRareOres) {
                ratio = (double) rareCount / totalCount;
                if (ratio >= rareOreRatioThreshold) {
                    // 알림 쿨다운 확인 (60초 이내 중복 알림 방지)
                    if (alertCooldown.getIfPresent(uuid) == null) {
                        isViolation = true;
                        alertCooldown.put(uuid, now);
                        // 적발 후 다음 표본을 위해 기록 초기화
                        history.clear();
                    }
                }
            }
        }

        if (isViolation) {
            String reason = String.format("비정상 광석 채굴 비율 감지 [다이아/잔해: %d개, 총 채굴: %d개, 비율: %.1f%%, 위치: %s %d, %d, %d]",
                    rareCount, totalCount, (ratio * 100.0),
                    worldName, x, y, z);
            securityManager.handleViolation(player, DefenseModule.ANTI_XRAY, reason);
            return true;
        }
        return false;
    }

    public BlockCategory categorize(Material material) {
        if (RARE_ORES.contains(material)) return BlockCategory.RARE_ORE;
        if (COMMON_ORES.contains(material)) return BlockCategory.COMMON_ORE;
        if (EXCAVATION_BLOCKS.contains(material)) return BlockCategory.EXCAVATION;
        return null;
    }

    public void recordPlacedOre(String world, int x, int y, int z) {
        placedOres.put(world + ":" + x + ":" + y + ":" + z, System.currentTimeMillis());
    }

    public boolean isPlacedOre(String world, int x, int y, int z) {
        return placedOres.getIfPresent(world + ":" + x + ":" + y + ":" + z) != null;
    }

    private String getBlockKey(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        miningHistory.remove(event.getPlayer().getUniqueId());
        alertCooldown.invalidate(event.getPlayer().getUniqueId());
        if (packetAntiXrayManager != null) {
            packetAntiXrayManager.onPlayerQuit(event.getPlayer());
        }
    }

    @Override
    public void close() {
        miningHistory.clear();
        placedOres.invalidateAll();
        alertCooldown.invalidateAll();
    }
}
