package com.l7defense.listener;

import com.l7defense.manager.SecurityManager;
import com.l7defense.module.DefenseModule;
import com.l7defense.module.ModuleConfigManager;
import com.l7defense.module.PenaltyAction;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AntiXrayTest {

    private SecurityManager securityManager;
    private ModuleConfigManager moduleManager;
    private AntiXrayListener listener;
    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() {
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

        // minTotalBlocks: 20, minRareOres: 5, threshold: 0.25 (25%)
        listener = new AntiXrayListener(securityManager, 20, 5, 0.25);
    }

    @Test
    @DisplayName("블록 카테고리 분류 검증")
    void testCategorization() {
        assertEquals(AntiXrayListener.BlockCategory.RARE_ORE, listener.categorize(Material.DIAMOND_ORE));
        assertEquals(AntiXrayListener.BlockCategory.RARE_ORE, listener.categorize(Material.DEEPSLATE_DIAMOND_ORE));
        assertEquals(AntiXrayListener.BlockCategory.RARE_ORE, listener.categorize(Material.ANCIENT_DEBRIS));
        assertEquals(AntiXrayListener.BlockCategory.RARE_ORE, listener.categorize(Material.EMERALD_ORE));

        assertEquals(AntiXrayListener.BlockCategory.COMMON_ORE, listener.categorize(Material.IRON_ORE));
        assertEquals(AntiXrayListener.BlockCategory.COMMON_ORE, listener.categorize(Material.GOLD_ORE));

        assertEquals(AntiXrayListener.BlockCategory.EXCAVATION, listener.categorize(Material.STONE));
        assertEquals(AntiXrayListener.BlockCategory.EXCAVATION, listener.categorize(Material.DEEPSLATE));
        assertEquals(AntiXrayListener.BlockCategory.EXCAVATION, listener.categorize(Material.NETHERRACK));

        assertNull(listener.categorize(Material.OAK_LOG));
        assertNull(listener.categorize(Material.CRAFTING_TABLE));
    }

    @Test
    @DisplayName("정상적인 채굴 패턴 (낮은 희귀 광석 비율) 시 경고 미발생")
    void testNormalMiningPattern() {
        // 돌 30개 채굴 + 다이아몬드 2개 채굴 (비율 약 6.25%)
        for (int i = 0; i < 30; i++) {
            assertFalse(listener.processMiningAction(player, Material.STONE, "world", i, 10, 0));
        }
        for (int i = 0; i < 2; i++) {
            assertFalse(listener.processMiningAction(player, Material.DIAMOND_ORE, "world", i, 10, 1));
        }

        verify(securityManager, never()).handleViolation(any(), eq(DefenseModule.ANTI_XRAY), anyString());
    }

    @Test
    @DisplayName("비정상적인 X-Ray 채굴 패턴 (다이아몬드 비율 30% 이상) 시 감지 및 경고 발동")
    void testSuspiciousXrayMiningPattern() {
        // 돌 14개 채굴
        for (int i = 0; i < 14; i++) {
            listener.processMiningAction(player, Material.DEEPSLATE, "world", i, -50, 0);
        }
        // 다이아몬드 6개 채굴 (총 20개 중 6개 = 30%)
        boolean violated = false;
        for (int i = 0; i < 6; i++) {
            if (listener.processMiningAction(player, Material.DEEPSLATE_DIAMOND_ORE, "world", i, -50, 1)) {
                violated = true;
            }
        }

        assertTrue(violated, "비정상 채굴 비율 도달 시 위반이 감지되어야 함");
        verify(securityManager, atLeastOnce()).handleViolation(eq(player), eq(DefenseModule.ANTI_XRAY), contains("비정상 광석 채굴 비율 감지"));
    }

    @Test
    @DisplayName("유저가 직접 설치한 광석은 실크터치/설치 어뷰징 방지 캐시로 인해 오탐 방지")
    void testPlacedOreDoesNotTriggerViolation() {
        World world = Mockito.mock(World.class);
        when(world.getName()).thenReturn("world");

        // 유저가 다이아몬드 원석을 6개 설치
        for (int i = 0; i < 6; i++) {
            Block block = Mockito.mock(Block.class);
            when(block.getType()).thenReturn(Material.DIAMOND_ORE);
            when(block.getWorld()).thenReturn(world);
            when(block.getX()).thenReturn(i);
            when(block.getY()).thenReturn(64);
            when(block.getZ()).thenReturn(0);

            BlockPlaceEvent placeEvent = new BlockPlaceEvent(block, null, null, null, player, true, null);
            listener.onBlockPlace(placeEvent);
            assertTrue(listener.isPlacedOre("world", i, 64, 0));
        }

        // 설치했던 다이아몬드 원석 6개를 다시 채굴
        for (int i = 0; i < 6; i++) {
            Block block = Mockito.mock(Block.class);
            when(block.getType()).thenReturn(Material.DIAMOND_ORE);
            when(block.getWorld()).thenReturn(world);
            when(block.getX()).thenReturn(i);
            when(block.getY()).thenReturn(64);
            when(block.getZ()).thenReturn(0);

            BlockBreakEvent breakEvent = new BlockBreakEvent(block, player);
            listener.onBlockBreak(breakEvent);
            // 채굴 후 캐시에서 제거되었는지 확인
            assertFalse(listener.isPlacedOre("world", i, 64, 0));
        }

        // 설치된 광석이었으므로 보안 매니저에 경고가 전달되지 않아야 함
        verify(securityManager, never()).handleViolation(any(), eq(DefenseModule.ANTI_XRAY), anyString());
    }

    @Test
    @DisplayName("크리에이티브 모드 플레이어는 채굴 분석 대상에서 제외")
    void testCreativeModeIgnored() {
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        World world = Mockito.mock(World.class);
        when(world.getName()).thenReturn("world");

        for (int i = 0; i < 30; i++) {
            Block block = Mockito.mock(Block.class);
            when(block.getType()).thenReturn(Material.DIAMOND_ORE);
            when(block.getWorld()).thenReturn(world);
            when(block.getX()).thenReturn(i);
            when(block.getY()).thenReturn(10);
            when(block.getZ()).thenReturn(0);

            BlockBreakEvent breakEvent = new BlockBreakEvent(block, player);
            listener.onBlockBreak(breakEvent);
        }

        verify(securityManager, never()).handleViolation(any(), eq(DefenseModule.ANTI_XRAY), anyString());
    }
}
