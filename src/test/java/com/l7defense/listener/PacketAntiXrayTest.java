package com.l7defense.listener;

import com.l7defense.manager.PacketAntiXrayManager;
import com.l7defense.manager.SecurityManager;
import com.l7defense.module.DefenseModule;
import com.l7defense.module.ModuleConfigManager;
import com.l7defense.module.PenaltyAction;
import com.l7defense.util.PaperAntiXrayConfigurator;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PacketAntiXrayTest {

    private Plugin plugin;
    private SecurityManager securityManager;
    private ModuleConfigManager moduleManager;
    private Player player;
    private UUID playerUuid;
    private World world;

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("PacketAntiXrayTest"));

        securityManager = Mockito.mock(SecurityManager.class);
        moduleManager = Mockito.mock(ModuleConfigManager.class);
        when(securityManager.getModuleManager()).thenReturn(moduleManager);
        when(moduleManager.isEnabled(DefenseModule.ANTI_XRAY)).thenReturn(true);
        when(moduleManager.getPenalty(DefenseModule.ANTI_XRAY)).thenReturn(PenaltyAction.NOTIFY);

        playerUuid = UUID.randomUUID();
        player = Mockito.mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn("TestMiner");
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);

        world = Mockito.mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
    }

    @Test
    @DisplayName("PaperAntiXrayConfigurator: config/paper-world-defaults.yml 에 Engine-Mode 2 자동 설정 적용 검증")
    void testPaperEngineMode2AutoConfig(@TempDir File tempDir) {
        assertFalse(PaperAntiXrayConfigurator.isEngineMode2Enabled(tempDir));

        // Engine-Mode 2 적용
        boolean success = PaperAntiXrayConfigurator.applyEngineMode2(tempDir, Logger.getLogger("test"));
        assertTrue(success);

        File configFile = new File(tempDir, "config/paper-world-defaults.yml");
        assertTrue(configFile.exists());

        // 설정 파일 내용 검증
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        assertTrue(config.getBoolean("anticheat.anti-xray.enabled"));
        assertEquals(2, config.getInt("anticheat.anti-xray.engine-mode"));
        assertEquals(2, config.getInt("anticheat.anti-xray.chunk-edge-mode"));
        assertTrue(config.getStringList("anticheat.anti-xray.hidden-blocks").contains("diamond_ore"));
        assertTrue(config.getStringList("anticheat.anti-xray.hidden-blocks").contains("ancient_debris"));

        assertTrue(PaperAntiXrayConfigurator.isEngineMode2Enabled(tempDir));
    }

    @Test
    @DisplayName("PacketAntiXrayManager: 6면이 밀폐된 블록(Occluded) 검사 로직 검증")
    void testIsFullyOccluded() {
        PacketAntiXrayManager manager = new PacketAntiXrayManager(plugin, securityManager, true, false, true, true);

        Block block = Mockito.mock(Block.class);
        Block solidNeighbor = Mockito.mock(Block.class);
        when(solidNeighbor.getType()).thenReturn(Material.STONE);

        // 6면 모두 고체 돌 블록으로 둘러싸임
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            when(block.getRelative(face)).thenReturn(solidNeighbor);
        }

        assertTrue(manager.isFullyOccluded(block));

        // 1면이 공기로 노출됨
        Block airNeighbor = Mockito.mock(Block.class);
        when(airNeighbor.getType()).thenReturn(Material.AIR);
        when(block.getRelative(BlockFace.UP)).thenReturn(airNeighbor);

        assertFalse(manager.isFullyOccluded(block));
    }

    @Test
    @DisplayName("PacketAntiXrayManager: 허니팟 가짜 다이아몬드 패킷 주입 및 ESP 채굴 적발 검증")
    void testDecoyTrapViolation() {
        PacketAntiXrayManager packetManager = new PacketAntiXrayManager(plugin, securityManager, true, false, true, true);
        AntiXrayListener listener = new AntiXrayListener(securityManager);
        listener.setPacketAntiXrayManager(packetManager);

        Location loc = new Location(world, 10, -30, 10);
        Block block = Mockito.mock(Block.class);
        when(block.getLocation()).thenReturn(loc);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(10);
        when(block.getY()).thenReturn(-30);
        when(block.getZ()).thenReturn(10);
        when(block.getType()).thenReturn(Material.STONE);
        BlockData stoneData = Mockito.mock(BlockData.class);
        when(block.getBlockData()).thenReturn(stoneData);

        // 허니팟 패킷 주입
        packetManager.injectDecoy(player, loc, Material.DIAMOND_ORE);
        assertTrue(packetManager.isDecoy(player, loc));

        // 유저가 가짜 광석을 타겟팅하여 채굴
        BlockBreakEvent breakEvent = new BlockBreakEvent(block, player);
        listener.onBlockBreak(breakEvent);

        // 이벤트가 취소되고 허니팟이 제거되며 보안 경고가 발생해야 함
        assertTrue(breakEvent.isCancelled());
        assertFalse(packetManager.isDecoy(player, loc));
        verify(securityManager, times(1)).handleViolation(eq(player), eq(DefenseModule.ANTI_XRAY), contains("X-Ray ESP 패킷 트랩 적발"));
    }
}
