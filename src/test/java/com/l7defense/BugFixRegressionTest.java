package com.l7defense;

import com.l7defense.module.DefenseModule;
import com.l7defense.module.ModuleConfigManager;
import com.l7defense.module.PenaltyAction;
import com.l7defense.util.Hostnames;
import com.l7defense.util.IpUtils;
import com.l7defense.util.NameEntropyChecker;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class BugFixRegressionTest {

    @Test
    @DisplayName("Hostnames: 다중 후행 점(trailing dots) 정규화 검증")
    void testHostnamesMultipleTrailingDots() {
        assertEquals("play.example.com", Hostnames.normalize("play.example.com..."));
        assertEquals("play.example.com", Hostnames.normalize("play.example.com.:25565"));
        assertTrue(Hostnames.matches(Hostnames.normalize("play.example.com..."), "example.com"));
    }

    @Test
    @DisplayName("NameEntropyChecker: 'y' 모음 포함 정상 닉네임 오탐(False Positive) 방지 검증")
    void testNameEntropyWithYVowels() {
        // 'y'가 모음군에 포함되어 연속 자음 5개 규칙에 의한 오탐이 발생하지 않아야 함
        assertFalse(NameEntropyChecker.isRandomBotName("SkyHunter"));
        assertFalse(NameEntropyChecker.isRandomBotName("RhythmMan"));
        assertFalse(NameEntropyChecker.isRandomBotName("FlyWarrior"));
    }

    @Test
    @DisplayName("NameEntropyChecker: 봇 및 알트 패턴 탐지 검증")
    void testNameEntropyBotDetection() {
        assertTrue(NameEntropyChecker.isRandomBotName("Bot12345"));
        assertTrue(NameEntropyChecker.isRandomBotName("BlueSky99")); // Alt generator pattern
        assertTrue(NameEntropyChecker.isRandomBotName("bcdefghjklmn")); // Consec consonants >= 5
    }

    @Test
    @DisplayName("IpUtils: 리터럴 검증 및 IPv6 패턴 검증")
    void testIpUtilsLiteralPatterns() {
        assertTrue(IpUtils.isLiteral("127.0.0.1"));
        assertTrue(IpUtils.isLiteral("::1"));
        assertTrue(IpUtils.isLiteral("2001:db8::1"));
        assertFalse(IpUtils.isLiteral(""));
        assertFalse(IpUtils.isLiteral(null));
        assertFalse(IpUtils.isLiteral("invalid.domain.name"));
        assertFalse(IpUtils.isLiteral("999.999.999.999"));
    }

    @Test
    @DisplayName("ModuleConfigManager: null 안전성 및 기본값 fallback 검증")
    void testModuleConfigManagerNullSafety() {
        Plugin plugin = Mockito.mock(Plugin.class);
        YamlConfiguration config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);

        ModuleConfigManager manager = new ModuleConfigManager(plugin);
        assertFalse(manager.isEnabled(null));
        assertEquals(PenaltyAction.NOTIFY, manager.getPenalty(null));

        // null 모듈 설정 시도시 예외가 발생하지 않아야 함
        assertDoesNotThrow(() -> manager.setEnabled(null, true));
        assertDoesNotThrow(() -> manager.setPenalty(null, PenaltyAction.KICK));
    }

    @Test
    @DisplayName("ChatAi: Unicode Bidi 및 다국어 정규화 정규식 검증")
    void testChatAiUnicodeBidiPattern() {
        Pattern bidiPattern = Pattern.compile("[\u200E\u200F\u202A-\u202E\u2066-\u2069\u061C]");
        assertTrue(bidiPattern.matcher("Hello \u202E World").find());
        assertTrue(bidiPattern.matcher("Crash \u2066 Test").find());
        assertTrue(bidiPattern.matcher("Arabic \u061C Test").find());
        assertFalse(bidiPattern.matcher("Normal Message 1234").find());

        // 다국어 문자 지원 검증 (한국어, 영어, 일본어, 유럽 문자 등)
        Pattern nonAlphaNumeric = Pattern.compile("[^\\p{L}\\p{N}]");
        assertEquals("hello1234", nonAlphaNumeric.matcher("Hello, 1234!").replaceAll("").toLowerCase(java.util.Locale.ROOT));
        assertEquals("안녕하세요12", nonAlphaNumeric.matcher("안녕하세요! 12").replaceAll("").toLowerCase(java.util.Locale.ROOT));
        assertEquals("こんにちは", nonAlphaNumeric.matcher("こんにちは!").replaceAll("").toLowerCase(java.util.Locale.ROOT));
        assertEquals("bonjour", nonAlphaNumeric.matcher("Bonjour!").replaceAll("").toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    @DisplayName("IpUtils: Unresolved 및 null InetSocketAddress 안전 처리 검증")
    void testIpUtilsSocketAddressSafety() {
        assertEquals("unknown", IpUtils.getIp((java.net.InetSocketAddress) null));
        java.net.InetSocketAddress addr = java.net.InetSocketAddress.createUnresolved("192.168.1.50", 25565);
        assertEquals("192.168.1.50", IpUtils.getIp(addr));
    }

    @Test
    @DisplayName("FirewallManager: 명령어 분할(quote) 파싱 검증")
    void testFirewallManagerSplitCommand() {
        java.util.List<String> parts = com.l7defense.util.FirewallManager.splitCommand("iptables -I INPUT -s 1.2.3.4 -j DROP");
        assertEquals(java.util.List.of("iptables", "-I", "INPUT", "-s", "1.2.3.4", "-j", "DROP"), parts);
    }

    @Test
    @DisplayName("NameEntropyChecker: 일반 영문 CamelCase 닉네임 오탐 방지 검증")
    void testNormalCamelCaseNamesNotFlagged() {
        assertFalse(NameEntropyChecker.isRandomBotName("MinecraftPro"));
        assertFalse(NameEntropyChecker.isRandomBotName("PlayerKnight"));
        assertFalse(NameEntropyChecker.isRandomBotName("SilverShadow"));
        assertFalse(NameEntropyChecker.isRandomBotName("KoreanGamer"));
    }

    @Test
    @DisplayName("ModuleConfigManager: 기존 키가 모두 존재할 때 불필요한 saveConfig() 미호출 검증")
    void testModuleConfigManagerSkipsSaveWhenClean() {
        Plugin plugin = Mockito.mock(Plugin.class);
        YamlConfiguration config = new YamlConfiguration();
        for (DefenseModule mod : DefenseModule.values()) {
            config.set("dynamic-modules." + mod.getId() + ".enabled", true);
            config.set("dynamic-modules." + mod.getId() + ".penalty", "NOTIFY");
        }
        when(plugin.getConfig()).thenReturn(config);

        new ModuleConfigManager(plugin);
        Mockito.verify(plugin, Mockito.never()).saveConfig();
    }

    @Test
    @DisplayName("MovementHeuristics: 바닐라 상/하단 클램핑(±90°) 시점 회전 오탐 방지 검증")
    void testMovementHeuristicsAllowsClampedPitch() throws Exception {
        try (var env = new TestServer();
             var listener = new com.l7defense.listener.MovementHeuristicsListener(env.security, true)) {
            when(env.modules.isEnabled(DefenseModule.MOVEMENT)).thenReturn(true);
            var player = env.player(java.util.UUID.randomUUID());
            var world = Mockito.mock(org.bukkit.World.class);

            // 바닥(90.0f) 및 하늘(-90.0f)을 보며 수평 회전해도 봇으로 오탐하지 않아야 함
            for (int i = 0; i < 15; i++) {
                float pitch = (i % 2 == 0) ? 90.0f : -90.0f;
                var from = new org.bukkit.Location(world, i, 64, 0, i * 20f, pitch);
                var to = new org.bukkit.Location(world, i + 0.2, 64, 0, (i + 1) * 20f, pitch);
                listener.onPlayerMove(new org.bukkit.event.player.PlayerMoveEvent(player, from, to));
            }
            assertNull(env.security.getCaptchas().get(player.getUniqueId()));
        }
    }

    @Test
    @DisplayName("ExploitCrasher: 정상 명령어 연속 자동완성 허용 및 대용량 탭 페이로드 차단 검증")
    void testExploitCrasherTabCompleteBalance() throws Exception {
        try (var env = new TestServer();
             var listener = new com.l7defense.listener.ExploitCrasherListener(env.security)) {
            when(env.modules.isEnabled(DefenseModule.EXPLOIT_CRASHER)).thenReturn(true);
            var player = env.player(java.util.UUID.randomUUID());

            // 15회 정상 타이핑 자동완성은 취소되지 않아야 함
            for (int i = 0; i < 15; i++) {
                var event = new org.bukkit.event.server.TabCompleteEvent(player, "/tp Player", java.util.List.of());
                listener.onTabComplete(event);
                assertFalse(event.isCancelled(), "정상 타이핑 자동완성이 차단되어서는 안 됨");
            }

            // 256자 초과 대용량 탭 버퍼는 즉시 차단
            var exploitEvent = new org.bukkit.event.server.TabCompleteEvent(player, "/" + "a".repeat(300), java.util.List.of());
            listener.onTabComplete(exploitEvent);
            assertTrue(exploitEvent.isCancelled());
        }
    }

    @Test
    @DisplayName("ChatAi: 정상 인사말 다중 입력 허용 및 실제 반복 스팸 차단 검증")
    void testChatAiAllowsGreetingsAndBlocksSpam() throws Exception {
        try (var env = new TestServer();
             var listener = new com.l7defense.listener.ChatAiListener(env.security)) {
            when(env.modules.isEnabled(DefenseModule.CHAT_AI)).thenReturn(true);
            var p1 = env.player(java.util.UUID.randomUUID());
            var p2 = env.player(java.util.UUID.randomUUID());
            var p3 = env.player(java.util.UUID.randomUUID());

            // 3명이 "안녕하세요"를 연달아 입력해도 차단되지 않아야 함
            for (var p : java.util.List.of(p1, p2, p3)) {
                var e = new org.bukkit.event.player.AsyncPlayerChatEvent(true, p, "안녕하세요", java.util.Set.of());
                listener.onPlayerChat(e);
                assertFalse(e.isCancelled(), "짧은 일반 인사말이 차단되어서는 안 됨");
            }

            // 동일 플레이어가 8자 이상 긴 스팸 문장을 3회 연속 도배 시 3번째는 차단
            var s1 = new org.bukkit.event.player.AsyncPlayerChatEvent(true, p1, "이것은테스트스팸도배문장입니다", java.util.Set.of());
            var s2 = new org.bukkit.event.player.AsyncPlayerChatEvent(true, p1, "이것은테스트스팸도배문장입니다", java.util.Set.of());
            var s3 = new org.bukkit.event.player.AsyncPlayerChatEvent(true, p1, "이것은테스트스팸도배문장입니다", java.util.Set.of());
            listener.onPlayerChat(s1);
            listener.onPlayerChat(s2);
            listener.onPlayerChat(s3);
            assertFalse(s1.isCancelled());
            assertFalse(s2.isCancelled());
            assertTrue(s3.isCancelled());
        }
    }
}
