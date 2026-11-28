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
        if (player == null) return;
        boolean isBrandEnabled = securityManager.getModuleManager() != null
                ? securityManager.getModuleManager().isEnabled(com.l7defense.module.DefenseModule.BRAND) : enabled;
        if (isBrandEnabled) {
            joinTimes.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    public void recordQuit(Player player) {
        if (player != null) {
            joinTimes.invalidate(player.getUniqueId());
        }
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, @NotNull byte[] message) {
        boolean isBrandEnabled = securityManager.getModuleManager() != null
                ? securityManager.getModuleManager().isEnabled(com.l7defense.module.DefenseModule.BRAND) : enabled;
        if (!isBrandEnabled || !channel.equals("minecraft:brand")) return;
        if (player.hasPermission("l7defense.admin")) return;

        joinTimes.invalidate(player.getUniqueId());

        // 1.20.2+ Configuration 페이즈에서는 정상 바닐라 클라이언트도 로그인 직후(0~15ms) brand를 전송하므로
        // 단순 지연 시간으로 오탐하지 않고, 비정상/악성 브랜드 페이로드 형식만 검증합니다.
        if (message.length == 0 || message.length > 256) {
            securityManager.challengeHeuristic(player, com.l7defense.module.DefenseModule.BRAND,
                    "비정상적 Client Brand 페이로드 크기 (" + message.length + " bytes)");
            return;
        }
        for (byte b : message) {
            if (b == 0) {
                securityManager.challengeHeuristic(player, com.l7defense.module.DefenseModule.BRAND,
                        "비정상적 Client Brand 페이로드 (Null 바이트 포함)");
                return;
            }
        }
    }

    @Override
    public void close() {
        joinTimes.invalidateAll();
    }
}
