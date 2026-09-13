package com.l7defense.util;

import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OS 레벨 방화벽 연동 매니저.
 * <p>
 * JVM 단에서 연결을 끊는 대신 iptables/ufw 등의 OS 명령어를 실행하여
 * Linux 커널 단위에서 패킷을 드롭시킵니다. CPU 부하를 극적으로 줄입니다.
 */
public final class FirewallManager {

    private final Plugin plugin;
    private final Logger logger;
    private final boolean enabled;
    private final String blockCommand;
    private final String unblockCommand;

    public FirewallManager(Plugin plugin, Logger logger, boolean enabled, String blockCommand, String unblockCommand) {
        this.plugin = plugin;
        this.logger = logger;
        this.enabled = enabled;
        this.blockCommand = blockCommand;
        this.unblockCommand = unblockCommand;
    }

    /** IP를 방화벽 명령어를 통해 차단합니다. (비동기 실행) */
    public void executeBlock(String ip) {
        if (!enabled || blockCommand.isBlank()) return;
        executeAsync(blockCommand.replace("{ip}", ip));
    }

    /** IP 차단을 해제합니다. (비동기 실행) */
    public void executeUnblock(String ip) {
        if (!enabled || unblockCommand.isBlank()) return;
        executeAsync(unblockCommand.replace("{ip}", ip));
    }

    private void executeAsync(String command) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Process process = Runtime.getRuntime().exec(command);
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    logger.warning("[방화벽] 명령어 실행 실패 (종료 코드: " + exitCode + "): " + command);
                } else {
                    logger.fine("[방화벽] 명령어 실행 성공: " + command);
                }
            } catch (IOException e) {
                logger.log(Level.WARNING, "[방화벽] 실행 오류 (권한 문제일 수 있음): " + command, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
