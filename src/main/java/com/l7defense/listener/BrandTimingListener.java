package com.l7defense.listener;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.l7defense.manager.SecurityManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 클라이언트 핑거프린팅 (Client Brand Timing Check).
 * <p>
 * 정상적인 마인크래프트 클라이언트(바닐라, 옵티파인, 포지 등)는 로그인 후
 * 약간의 렌더링 딜레이를 거친 뒤 'minecraft:brand' 메시지를 전송합니다.
 * 봇 스크립트는 이 패킷을 누락하거나 로그인 패킷과 정확히 0ms 차이로 동시에 보냅니다.
 */
public final class BrandTimingListener implements PluginMessageListener, AutoCloseable {

    private final Plugin plugin;
    private final SecurityManager securityManager;
    private final boolean enabled;

    // 유저의 Join 타임스탬프 기록 (메모리 누수 방지 위해 30초 후 만료)
    private final Cache<UUID, Long> joinTimes = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(5000)
            .build();

    public BrandTimingListener(Plugin plugin, SecurityManager securityManager, boolean enabled) {
        this.plugin = plugin;
        this.securityManager = securityManager;
        this.enabled = enabled;
    }

    public void recordJoin(Player player) {
        if (player != null && (enabled || (securityManager.getModuleManager() != null && securityManager.getModuleManager().isEnabled(com.l7defense.module.DefenseModule.BRAND)))) {
            joinTimes.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    public void recordQuit(Player player) {
        if (enabled && player != null) {
            joinTimes.invalidate(player.getUniqueId());
        }
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, @NotNull byte[] message) {
        if (!enabled || securityManager.getModuleManager() == null 
                || !securityManager.getModuleManager().isEnabled(com.l7defense.module.DefenseModule.BRAND)
                || !channel.equals("minecraft:brand")) return;

        Long joinTime = joinTimes.asMap().remove(player.getUniqueId());
        if (joinTime != null) {
            long delay = System.currentTimeMillis() - joinTime;

            // 15ms 미만의 딜레이는 기계(봇 스크립트)로 간주
            if (delay < 15L) {
                securityManager.challengeHeuristic(player, com.l7defense.module.DefenseModule.BRAND,
                        "비정상적 페이로드 타이밍 (Client Brand Spoof)");
            } else {
                // 정상 클라이언트: 화이트리스트 점수 부여 또는 안전 통과
            }
        }
    }

    @Override
    public void close() {
        joinTimes.invalidateAll();
    }
}
