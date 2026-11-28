package com.l7defense.listener;
import com.l7defense.util.IpUtils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.l7defense.manager.SecurityManager;
import com.l7defense.module.DefenseModule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 지능형 챗봇 필터 (v1.7)
 * <p>
 * 1. Levenshtein Distance (문자열 편집 거리)를 이용한 유사도 기반 스팸 차단
 * 2. 아랍어 등 RTL(Right-To-Left) 및 Unicode Bidi 특수문자를 이용한 마인크래프트 클라이언트 렌더링 크래시 방어
 * 3. 기계적 패턴(동일 글자 반복 등) 도배 방어
 */
public final class ChatAiListener implements Listener, AutoCloseable {

    private static final Pattern BIDI_OVERRIDE_PATTERN = Pattern.compile("[\u200E\u200F\u202A-\u202E\u2066-\u2069\u061C]");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]");

    private final SecurityManager securityManager;

    private record GlobalChatEntry(String senderId, String normalizedMsg) {}
    private record UserChatEntry(String normalizedMsg, int repeatCount) {}

    // 글로벌(서버 전체) 최근 채팅 보관하여 봇넷 단위의 다중 계정 도배를 감지합니다.
    private final Cache<Long, GlobalChatEntry> globalRecentChats;

    // 유저 개인별 최근 채팅 캐시 (짧은 시간 내 연속 채팅 방지)
    private final Cache<String, UserChatEntry> userRecentChats;
    private final java.util.concurrent.atomic.AtomicLong messageId = new java.util.concurrent.atomic.AtomicLong();

    public ChatAiListener(SecurityManager securityManager) {
        this.securityManager = securityManager;
        this.globalRecentChats = Caffeine.newBuilder().expireAfterWrite(15, TimeUnit.SECONDS).maximumSize(30).build();
        this.userRecentChats = Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.SECONDS).maximumSize(1000).build();
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (securityManager.getModuleManager() == null || !securityManager.getModuleManager().isEnabled(DefenseModule.CHAT_AI)) return;

        Player player = event.getPlayer();
        String message = event.getMessage();
        if (player == null || message == null) return;

        var session = securityManager.getSessions() != null ? securityManager.getSessions().get(player) : null;
        if (session == null || session.admin()) return;
        String playerId = session.id().toString(); // 개인 도배는 공유 IP가 아닌 플레이어별 집계
        if (message.length() > 256) {
            event.setCancelled(true);
            securityManager.handleViolation(player, DefenseModule.EXPLOIT_CRASHER, "비정상적 채팅 길이");
            return;
        }

        // 1. 악성 렌더링 크래시 방어 (RTL / Bidi Override 특수문자 차단)
        if (BIDI_OVERRIDE_PATTERN.matcher(message).find()) {
            event.setCancelled(true);
            securityManager.handleViolation(player, DefenseModule.EXPLOIT_CRASHER, "RTL/Bidi 오버라이드 채팅 크래셔 감지");
            return;
        }

        // 2. 메시지 유사도 분석
        String normalizedMsg = normalizeForAnalysis(message);

        // 짧은 일반 인사/감탄사("안녕하세요", "ㅋㅋㅋㅋㅋ", "감사합니다" 등)는 오탐 방지를 위해 제외
        if (normalizedMsg.length() < 8) return;

        // 개인 도배 검사: 10초 내 유사도 88% 이상 문장을 3회 연속(2회 이상 반복) 입력 시 차단
        UserChatEntry lastUser = userRecentChats.getIfPresent(playerId);
        int nextRepeatCount = 1;
        if (lastUser != null) {
            double similarity = calculateSimilarity(normalizedMsg, lastUser.normalizedMsg());
            if (similarity > 0.88) {
                nextRepeatCount = lastUser.repeatCount() + 1;
                userRecentChats.put(playerId, new UserChatEntry(normalizedMsg, nextRepeatCount));
                if (nextRepeatCount >= 3) {
                    event.setCancelled(true);
                    securityManager.handleViolation(player, DefenseModule.CHAT_AI, "유사도 높은 문장 반복 스팸 (" + Math.round(similarity * 100) + "%)");
                    return;
                }
            }
        }

        // 글로벌 봇넷 도배 검사: 15초 내 서로 다른 3명 이상의 계정이 10자 이상 유사 문장(85%+)을 동시 도배 시 차단
        if (normalizedMsg.length() >= 10) {
            java.util.Set<String> distinctSenders = new java.util.HashSet<>();
            for (GlobalChatEntry past : globalRecentChats.asMap().values()) {
                if (past.senderId().equals(playerId)) continue;
                double similarity = calculateSimilarity(normalizedMsg, past.normalizedMsg());
                if (similarity > 0.85) {
                    distinctSenders.add(past.senderId());
                }
            }
            if (distinctSenders.size() >= 3) {
                event.setCancelled(true);
                securityManager.handleViolation(player, DefenseModule.CHAT_AI, "글로벌 봇넷 채팅 스팸 패턴 감지");
                return;
            }
        }

        // 정상 채팅 통과 시 캐시에 기록
        userRecentChats.put(playerId, new UserChatEntry(normalizedMsg, nextRepeatCount));
        globalRecentChats.put(messageId.incrementAndGet(), new GlobalChatEntry(playerId, normalizedMsg));
    }

    /**
     * 비교를 위해 문자열에서 공백과 특수문자를 제거하고 소문자로 만듭니다.
     * (예: "J o i n  H.e.r.e!" -> "joinhere")
     */
    private String normalizeForAnalysis(String str) {
        return NON_ALPHANUMERIC.matcher(str).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /**
     * 두 문자열 간의 유사도를 Levenshtein Distance(편집 거리)를 이용해 0.0 ~ 1.0 비율로 계산합니다.
     */
    private double calculateSimilarity(String s1, String s2) {
        int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) return 1.0;

        int distance = computeLevenshteinDistance(s1, s2);
        return 1.0 - ((double) distance / maxLen);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        userRecentChats.invalidate(event.getPlayer().getUniqueId().toString());
    }

    @Override
    public void close() {
        userRecentChats.invalidateAll();
        globalRecentChats.invalidateAll();
    }

    private int computeLevenshteinDistance(String s1, String s2) {
        int[] costs = new int[s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) {
            int lastValue = i;
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    costs[j] = j;
                } else {
                    if (j > 0) {
                        int newValue = costs[j - 1];
                        if (s1.charAt(i - 1) != s2.charAt(j - 1)) {
                            newValue = Math.min(Math.min(newValue, lastValue), costs[j]) + 1;
                        }
                        costs[j - 1] = lastValue;
                        lastValue = newValue;
                    }
                }
            }
            if (i > 0) costs[s2.length()] = lastValue;
        }
        return costs[s2.length()];
    }
}
