package com.l7defense.listener;

import com.l7defense.manager.SecurityManager;
import com.l7defense.module.DefenseModule;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

/**
 * v1.9 Client Mod Spoofing & WorldDownloader (WDL) 차단 시스템
 * 클라이언트가 숨겨서 보내는 모드(Mod) 통신 채널을 감지하여 불법 맵 추출 및 스푸핑을 차단합니다.
 */
public class ClientModDetectorListener implements PluginMessageListener {

    private final SecurityManager securityManager;

    public ClientModDetectorListener(SecurityManager securityManager) {
        this.securityManager = securityManager;
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, @NotNull byte[] message) {
        // 1. 월드 다운로더 (WDL) 및 맵 스틸러 감지
        if (securityManager.getModuleManager().isEnabled(DefenseModule.MAP_STEALER)) {
            String lowerChannel = channel.toLowerCase();
            if (lowerChannel.equals("wdl|init") || lowerChannel.equals("wdl|control") ||
                lowerChannel.equals("wdl:init") || lowerChannel.equals("wdl:control") ||
                lowerChannel.equals("worlddownloader:init")) {
                
                securityManager.handleViolation(player, DefenseModule.MAP_STEALER, "월드 다운로더(WDL) 맵 렌더러 사용 적발");
            }
        }
    }
}
