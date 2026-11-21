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
}
