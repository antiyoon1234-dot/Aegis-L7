package com.l7defense.listener;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.l7defense.manager.SecurityManager;
import com.l7defense.module.DefenseModule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 지능형 챗봇 필터 (v1.7)
 * <p>
 * 1. Levenshtein Distance (문자열 편집 거리)를 이용한 유사도 기반 스팸 차단
 * 2. 아랍어 등 RTL(Right-To-Left) 특수문자를 이용한 마인크래프트 클라이언트 렌더링 크래시 방어
 * 3. 기계적 패턴(동일 글자 반복 등) 도배 방어
 */
public final class ChatAiListener implements Listener {

    private final SecurityManager securityManager;
    
    // 글로벌(서버 전체) 최근 채팅 5개를 보관하여 봇넷 단위의 다중 계정 도배를 감지합니다.
    private final Cache<Long, String> globalRecentChats;
    
    // 유저 개인별 최근 채팅 캐시 (짧은 시간 내 연속 채팅 방지)
    private final Cache<String, String> userRecentChats;

    public ChatAiListener(SecurityManager securityManager) {
        this.securityManager = securityManager;
        this.globalRecentChats = Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.SECONDS).maximumSize(20).build();
        this.userRecentChats = Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.SECONDS).maximumSize(1000).build();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!securityManager.getModuleManager().isEnabled(DefenseModule.CHAT_AI)) return;

        Player player = event.getPlayer();
        String message = event.getMessage();
        String ip = player.getAddress().getAddress().getHostAddress();

        // 1. 악성 렌더링 크래시 방어 (RTL Override 특수문자 차단)
        // \u202E, \u202D 등은 마인크래프트 채팅창 렌더링을 터뜨리거나 반전시킵니다.
        if (message.contains("\u202E") || message.contains("\u202D") || message.contains("\u200F")) {
            event.setCancelled(true);
            securityManager.handleViolation(player, DefenseModule.EXPLOIT_CRASHER, "RTL 오버라이드 채팅 크래셔 감지");
            return;
        }

        // 2. 메시지 유사도 (머신러닝) 분석
        String normalizedMsg = normalizeForAnalysis(message);
        
        // 너무 짧은 채팅은 유사도 분석에서 제외
        if (normalizedMsg.length() < 5) return;

        // 개인 도배 검사 (자기 자신이 똑같은/비슷한 말을 계속 반복하는가?)
        String lastUserMsg = userRecentChats.getIfPresent(ip);
        if (lastUserMsg != null) {
            double similarity = calculateSimilarity(normalizedMsg, lastUserMsg);
            if (similarity > 0.85) { // 85% 이상 일치하면 차단
                event.setCancelled(true);
                securityManager.handleViolation(player, DefenseModule.CHAT_AI, "유사도 높은 문장 반복 스팸 (" + Math.round(similarity * 100) + "%)");
                return;
            }
        }
        
        // 글로벌 봇넷 도배 검사 (서로 다른 봇들이 비슷한 말을 계속 올리는가?)
        int similarCount = 0;
        for (String pastMsg : globalRecentChats.asMap().values()) {
            double similarity = calculateSimilarity(normalizedMsg, pastMsg);
            if (similarity > 0.80) {
                similarCount++;
            }
        }
        
        // 3명(번) 이상이 최근 30초 내에 80% 이상 유사한 문장을 썼다면 봇넷 스팸 공격으로 간주
        if (similarCount >= 2) {
            event.setCancelled(true);
            securityManager.handleViolation(player, DefenseModule.CHAT_AI, "글로벌 봇넷 채팅 스팸 패턴 감지");
            return;
        }

        // 정상 채팅 통과 시 캐시에 기록
        userRecentChats.put(ip, normalizedMsg);
        globalRecentChats.put(System.currentTimeMillis(), normalizedMsg);
    }

    /**
     * 비교를 위해 문자열에서 공백과 특수문자를 제거하고 소문자로 만듭니다.
     * (예: "J o i n  H.e.r.e!" -> "joinhere")
     */
    private String normalizeForAnalysis(String str) {
        return str.replaceAll("[^a-zA-Z0-9가-힣]", "").toLowerCase();
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
