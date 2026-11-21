package com.l7defense.manager;

import com.l7defense.listener.AntiXrayListener;
import com.l7defense.module.DefenseModule;
import com.l7defense.util.PaperAntiXrayConfigurator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * 패킷 단위 광석 난수화 및 위장/은닉 방어 매니저
 * 
 * 1. Paper 서버의 패킷 난수화(Engine-Mode 2) 설정을 자동 적용하고 동기화합니다.
 * 2. 실시간 클라이언트 패킷 마스킹: 지하에 완전히 밀폐된 미노출 광석을 패킷 레벨에서 돌/심층암으로 위장 전송하여
 *    클라이언트 엑스레이 텍스처팩 및 ESP가 광석을 투시할 수 없도록 원천 차단합니다.
 * 3. 패킷 허니팟(Decoy Traps): 벽 너머 밀폐된 바위에 가짜 다이아몬드 패킷을 주입하여, ESP를 켜고 직진 채굴하는 유저를 즉각 적발합니다.
 */
public class PacketAntiXrayManager implements AutoCloseable {

    private final Plugin plugin;
    private final SecurityManager securityManager;
    private final Logger logger;

    private boolean enabled;
    private boolean clientMaskingEnabled;
    private boolean decoysEnabled;
    private int maxDecoysPerPlayer;
    private int decoyMinDistance;
    private int decoyMaxDistance;

    // 인접 6방향
    private static final BlockFace[] FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    // 유저별 마스킹된 광석 위치 보관 (UUID -> Set<Location>)
    private final Map<UUID, Set<Location>> maskedBlocks = new ConcurrentHashMap<>();

    // 유저별 허니팟 위장 패킷 위치 보관 (UUID -> Map<Location, Material>)
    private final Map<UUID, Map<Location, Material>> activeDecoys = new ConcurrentHashMap<>();

    // 유저별 패킷 스캔 쿨다운 (최대 1.5초당 1회)
    private final Map<UUID, Long> lastScanTime = new ConcurrentHashMap<>();

    public PacketAntiXrayManager(Plugin plugin, SecurityManager securityManager, boolean enabled,
                                boolean autoConfigurePaper, boolean clientMaskingEnabled, boolean decoysEnabled) {
        this(plugin, securityManager, enabled, autoConfigurePaper, clientMaskingEnabled, decoysEnabled, 3, 4, 8);
    }

    public PacketAntiXrayManager(Plugin plugin, SecurityManager securityManager, boolean enabled,
                                boolean autoConfigurePaper, boolean clientMaskingEnabled, boolean decoysEnabled,
                                int maxDecoysPerPlayer, int decoyMinDistance, int decoyMaxDistance) {
        this.plugin = plugin;
        this.securityManager = securityManager;
        this.logger = plugin.getLogger();
        this.enabled = enabled;
        this.clientMaskingEnabled = clientMaskingEnabled;
        this.decoysEnabled = decoysEnabled;
        this.maxDecoysPerPlayer = Math.max(1, maxDecoysPerPlayer);
        this.decoyMinDistance = Math.max(2, decoyMinDistance);
        this.decoyMaxDistance = Math.max(this.decoyMinDistance + 1, decoyMaxDistance);

        if (enabled && autoConfigurePaper) {
            setupPaperEngineMode2();
        }
    }

    public void setupPaperEngineMode2() {
        File serverDir = Bukkit.getWorldContainer();
        if (serverDir == null) serverDir = new File(".");
        PaperAntiXrayConfigurator.applyEngineMode2(serverDir, logger);
    }

    public boolean isPaperEngineMode2Active() {
        File serverDir = Bukkit.getWorldContainer();
        if (serverDir == null) serverDir = new File(".");
        return PaperAntiXrayConfigurator.isEngineMode2Enabled(serverDir);
    }

    /**
     * 플레이어가 블록을 채굴했을 때 주변 미노출 광석을 패킷 단위로 마스킹하고,
     * 파괴된 블록 주변의 마스킹된 광석은 즉시 클라이언트에게 원래 모습으로 복원합니다.
     * 또한 주변 밀폐된 암석에 가짜 다이아몬드 허니팟을 자동 주입합니다.
     */
    public void onBlockAction(Player player, Block brokenBlock) {
        if (!enabled) return;

        // 1. 파괴된 블록과 인접한 마스킹 광석 복원
        revealAdjacentBlocks(player, brokenBlock);

        // 2. 지하에 위치한 유저 주변의 미노출 광석 스캔 및 허니팟 주입 (1.5초 쿨다운)
        long now = System.currentTimeMillis();
        Long last = lastScanTime.get(player.getUniqueId());
        if (last == null || now - last >= 1500) {
            lastScanTime.put(player.getUniqueId(), now);
            if (clientMaskingEnabled) {
                scanAndMaskHiddenOres(player, brokenBlock.getLocation());
            }
            if (decoysEnabled) {
                deployAutomaticDecoys(player, brokenBlock.getLocation());
            }
        }
    }

    /**
     * 파괴된 블록에 인접한 6개 면을 검사하여, 마스킹되어 있던 광석을 원래 모습으로 클라이언트에게 복원 전송합니다.
     * 주의: 가짜 허니팟은 유저가 타겟팅하여 채굴했을 때 적발하기 위해 인접 블록 파괴 시 제거하지 않습니다.
     */
    public void revealAdjacentBlocks(Player player, Block brokenBlock) {
        if (brokenBlock == null) return;
        Location baseLoc = brokenBlock.getLocation();
        Set<Location> playerMasked = maskedBlocks.get(player.getUniqueId());

        for (BlockFace face : FACES) {
            Block neighbor = brokenBlock.getRelative(face);
            if (neighbor == null) continue;
            Location nLoc = neighbor.getLocation();
            if (nLoc == null) continue;

            // 마스킹 복구
            if (playerMasked != null && playerMasked.remove(nLoc)) {
                if (Bukkit.getServer() != null) {
                    try { player.sendBlockChange(nLoc, neighbor.getBlockData()); } catch (Throwable ignored) {}
                }
            }
        }
    }

    /**
     * 지하(Y < 64 또는 네더)에서 채굴 중인 유저 반경 내의 완전히 밀폐된 광석을 탐색하여
     * 클라이언트에게 돌/심층암/네더랙 패킷으로 위장(마스킹)합니다.
     */
    public void scanAndMaskHiddenOres(Player player, Location center) {
        World world = center.getWorld();
        if (world == null) return;

        int playerY = center.getBlockY();
        boolean isNether = world.getEnvironment() == World.Environment.NETHER;
        if (!isNether && playerY >= 64) return;

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        Set<Location> playerMasked = maskedBlocks.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        // 장시간 채굴 시 메모리 누적 방지: 32블록 이상 멀어진 과거 마스킹 블록 정리
        if (playerMasked.size() > 300) {
            playerMasked.removeIf(loc -> loc.getWorld() != world || loc.distanceSquared(center) > 1024);
        }

        // 반경 X/Z: 6, Y: 3
        int radiusXZ = 6;
        int radiusY = 3;

        for (int dx = -radiusXZ; dx <= radiusXZ; dx++) {
            for (int dy = -radiusY; dy <= radiusY; dy++) {
                for (int dz = -radiusXZ; dz <= radiusXZ; dz++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;

                    Block block = world.getBlockAt(x, y, z);
                    Material mat = block.getType();

                    if (AntiXrayListener.RARE_ORES.contains(mat)) {
                        Location loc = block.getLocation();
                        if (isFullyOccluded(block) && !playerMasked.contains(loc)) {
                            // 완전히 밀폐된 광석이면 돌/심층암/네더랙으로 위장 패킷 전송
                            if (Bukkit.getServer() != null) {
                                try {
                                    BlockData fakeData = getMaskBlockData(world, y);
                                    player.sendBlockChange(loc, fakeData);
                                } catch (Throwable ignored) {}
                            }
                            playerMasked.add(loc);
                        }
                    }
                }
            }
        }
    }

    /**
     * 해당 블록의 6면이 모두 빛을 완전히 차단하는 고체 암석으로 덮여 있는지 검사합니다.
     * 공기, 물, 용암, 유리 등 투명 블록과 닿아있지 않아야 유효한 은닉 대상입니다.
     */
    public boolean isFullyOccluded(Block block) {
        for (BlockFace face : FACES) {
            Block rel = block.getRelative(face);
            Material type = rel.getType();
            if (type.isAir() || !type.isOccluding()) {
                return false;
            }
        }
        return true;
    }

    private BlockData getMaskBlockData(World world, int y) {
        if (world.getEnvironment() == World.Environment.NETHER) {
            return Material.NETHERRACK.createBlockData();
        } else if (y < 0) {
            return Material.DEEPSLATE.createBlockData();
        } else {
            return Material.STONE.createBlockData();
        }
    }

    /**
     * 가짜 허니팟 광석 패킷 여부를 확인합니다.
     */
    public boolean isDecoy(Player player, Location loc) {
        Map<Location, Material> decoys = activeDecoys.get(player.getUniqueId());
        return decoys != null && decoys.containsKey(loc);
    }

    /**
     * 허니팟 패킷을 제거하고 실제 블록 상태를 클라이언트에 전송합니다.
     */
    public void removeDecoy(Player player, Location loc) {
        Map<Location, Material> decoys = activeDecoys.get(player.getUniqueId());
        if (decoys != null) {
            decoys.remove(loc);
            if (Bukkit.getServer() != null) {
                try {
                    Block block = loc.getBlock();
                    player.sendBlockChange(loc, block.getBlockData());
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * 테스트 및 허니팟 주입용 메서드
     */
    public void injectDecoy(Player player, Location loc, Material fakeOre) {
        Map<Location, Material> decoys = activeDecoys.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
        decoys.put(loc, fakeOre);
        if (Bukkit.getServer() != null) {
            try {
                player.sendBlockChange(loc, fakeOre.createBlockData());
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 유저 주변의 밀폐된 암석 속에 가짜 다이아몬드/고대잔해 허니팟 패킷을 자동 주입합니다.
     * 일반적인 채광자는 볼 수 없으며, 엑스레이(ESP/텍스처팩)를 켠 유저만 투시하여 파고들게 됩니다.
     */
    public void deployAutomaticDecoys(Player player, Location center) {
        if (!enabled || !decoysEnabled || player == null || !player.isOnline()) return;
        World world = center.getWorld();
        if (world == null) return;

        int playerY = center.getBlockY();
        boolean isNether = world.getEnvironment() == World.Environment.NETHER;
        // 오버월드의 경우 지하(Y < 64), 네더의 경우 전 영역 유효
        if (!isNether && playerY >= 64) return;

        Map<Location, Material> decoys = activeDecoys.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());

        // 유저와 20블록 이상 멀어진 기존 허니팟 정리 (메모리 및 리소스 누적 방지)
        decoys.keySet().removeIf(loc -> {
            if (loc.getWorld() != world || loc.distanceSquared(center) > 400) {
                if (Bukkit.getServer() != null) {
                    try { player.sendBlockChange(loc, loc.getBlock().getBlockData()); } catch (Throwable ignored) {}
                }
                return true;
            }
            return false;
        });

        if (decoys.size() >= maxDecoysPerPlayer) {
            return;
        }

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        int needed = maxDecoysPerPlayer - decoys.size();
        int attempts = 0;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        while (needed > 0 && attempts < 30) {
            attempts++;
            int dx = rng.nextInt(decoyMinDistance, decoyMaxDistance + 1) * (rng.nextBoolean() ? 1 : -1);
            int dy = rng.nextInt(-3, 4);
            int dz = rng.nextInt(decoyMinDistance, decoyMaxDistance + 1) * (rng.nextBoolean() ? 1 : -1);

            int targetX = cx + dx;
            int targetY = cy + dy;
            int targetZ = cz + dz;

            if (targetY < world.getMinHeight() + 2 || targetY > world.getMaxHeight() - 2) continue;

            Block candidate = world.getBlockAt(targetX, targetY, targetZ);
            Material type = candidate.getType();

            if (!isStandardGroundRock(type)) continue;
            if (!isFullyOccluded(candidate)) continue;

            Location candidateLoc = candidate.getLocation();
            if (decoys.containsKey(candidateLoc)) continue;

            Material fakeOre;
            if (isNether) {
                fakeOre = Material.ANCIENT_DEBRIS;
            } else if (targetY < 0) {
                fakeOre = Material.DEEPSLATE_DIAMOND_ORE;
            } else {
                fakeOre = Material.DIAMOND_ORE;
            }

            injectDecoy(player, candidateLoc, fakeOre);
            needed--;
        }
    }

    public static boolean isStandardGroundRock(Material type) {
        return type == Material.STONE || type == Material.DEEPSLATE || type == Material.TUFF
                || type == Material.ANDESITE || type == Material.DIORITE || type == Material.GRANITE
                || type == Material.NETHERRACK || type == Material.BLACKSTONE || type == Material.BASALT;
    }

    public void clearDecoys(Player player) {
        Map<Location, Material> decoys = activeDecoys.remove(player.getUniqueId());
        if (decoys != null && Bukkit.getServer() != null) {
            for (Location loc : decoys.keySet()) {
                if (loc.getWorld() != null) {
                    try { player.sendBlockChange(loc, loc.getBlock().getBlockData()); } catch (Throwable ignored) {}
                }
            }
        }
    }

    public int getActiveDecoyCount(UUID uuid) {
        Map<Location, Material> map = activeDecoys.get(uuid);
        return map != null ? map.size() : 0;
    }

    public int getMaxDecoysPerPlayer() { return maxDecoysPerPlayer; }
    public void setMaxDecoysPerPlayer(int maxDecoysPerPlayer) { this.maxDecoysPerPlayer = maxDecoysPerPlayer; }
    public int getDecoyMinDistance() { return decoyMinDistance; }
    public void setDecoyMinDistance(int decoyMinDistance) { this.decoyMinDistance = decoyMinDistance; }
    public int getDecoyMaxDistance() { return decoyMaxDistance; }
    public void setDecoyMaxDistance(int decoyMaxDistance) { this.decoyMaxDistance = decoyMaxDistance; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isClientMaskingEnabled() { return clientMaskingEnabled; }
    public void setClientMaskingEnabled(boolean clientMaskingEnabled) { this.clientMaskingEnabled = clientMaskingEnabled; }
    public boolean isDecoysEnabled() { return decoysEnabled; }
    public void setDecoysEnabled(boolean decoysEnabled) { this.decoysEnabled = decoysEnabled; }

    public Set<Location> getMaskedBlocks(UUID uuid) {
        return maskedBlocks.getOrDefault(uuid, Collections.emptySet());
    }

    public void onPlayerQuit(Player player) {
        UUID uuid = player.getUniqueId();
        maskedBlocks.remove(uuid);
        activeDecoys.remove(uuid);
        lastScanTime.remove(uuid);
    }

    @Override
    public void close() {
        if (Bukkit.getServer() != null) {
            try {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    Set<Location> masked = maskedBlocks.remove(player.getUniqueId());
                    if (masked != null) {
                        for (Location loc : masked) {
                            if (loc.getWorld() != null) {
                                player.sendBlockChange(loc, loc.getBlock().getBlockData());
                            }
                        }
                    }
                    Map<Location, Material> decoys = activeDecoys.remove(player.getUniqueId());
                    if (decoys != null) {
                        for (Location loc : decoys.keySet()) {
                            if (loc.getWorld() != null) {
                                player.sendBlockChange(loc, loc.getBlock().getBlockData());
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        maskedBlocks.clear();
        activeDecoys.clear();
        lastScanTime.clear();
    }
}
