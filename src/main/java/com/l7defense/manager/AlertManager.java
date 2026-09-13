package com.l7defense.manager;

import com.l7defense.L7DefensePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OP 실시간 공격 경고 알림 매니저.
 * <p>
 * Bukkit Scheduler를 이용해 5초(100틱)마다 공격 상태를 확인하고,
 * 알림이 활성화된 온라인 OP 플레이어에게 경고 메시지를 전송합니다.
 * <p>
 * 플레이어별 토글 상태는 {@link ConcurrentHashMap} 기반 {@link Set}으로
 * 스레드 안전하게 관리됩니다.
 */
public final class AlertManager {

    private final L7DefensePlugin plugin;
    private final SecurityManager securityManager;

    /** 알림을 비활성화한 플레이어 UUID 집합 (기본값: 알림 활성화) */
    private final Set<UUID> mutedPlayers = ConcurrentHashMap.newKeySet();

    /** 전역 알림 활성화 상태 */
    private volatile boolean globalAlertEnabled = true;

    /** 스케줄러 태스크 참조 (취소 용도) */
    private BukkitTask alertTask;

    // ── 메시지 프리픽스 컴포넌트 ────────────────────────────
    private static final Component PREFIX = Component.text("[L7 보안경고] ", NamedTextColor.RED)
            .decoration(TextDecoration.BOLD, true);

    public AlertManager(L7DefensePlugin plugin, SecurityManager securityManager) {
        this.plugin = plugin;
        this.securityManager = securityManager;
    }

    /**
     * 알림 스케줄러를 시작합니다.
     * <p>
     * 설정된 간격마다 반복 실행되며, 메인 스레드에서 플레이어에게 메시지를 전송합니다.
     * Bukkit API의 플레이어 접근은 반드시 메인 스레드에서 수행해야 합니다.
     *
     * @param intervalTicks 알림 전송 간격 (틱 단위, 20틱 = 1초)
     */
    public void startAlertScheduler(long intervalTicks) {
        // 이전 태스크가 있으면 취소
        stopAlertScheduler();

        alertTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // 전역 알림이 꺼져있거나 공격 상태가 아니면 스킵
            if (!globalAlertEnabled || !securityManager.isUnderAttack()) {
                return;
            }

            // 경고 메시지 컴포넌트 구성
            long lifetimeBlocked = securityManager.getLifetimeBlockedCount();
            long recentBlocks = securityManager.getRecentBlockCount();
            long activeBlocked = securityManager.getActiveBlockedCount();

            Component alertMessage = PREFIX
                    .append(Component.text("현재 서버가 공격받고 있습니다. ", NamedTextColor.YELLOW))
                    .append(Component.text("(최근 차단: ", NamedTextColor.GRAY))
                    .append(Component.text(recentBlocks + "건", NamedTextColor.WHITE)
                            .decoration(TextDecoration.BOLD, true))
                    .append(Component.text(", 활성 차단: ", NamedTextColor.GRAY))
                    .append(Component.text(activeBlocked + "건", NamedTextColor.WHITE)
                            .decoration(TextDecoration.BOLD, true))
                    .append(Component.text(", 최근 접속 시도 폭주 중)", NamedTextColor.GRAY));

            // 온라인 OP 플레이어에게 전송 (알림 비활성화 플레이어 제외)
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOp() && !mutedPlayers.contains(player.getUniqueId())) {
                    player.sendMessage(alertMessage);
                }
            }
        }, intervalTicks, intervalTicks);
    }

    /** 알림 스케줄러를 중지합니다. */
    public void stopAlertScheduler() {
        if (alertTask != null && !alertTask.isCancelled()) {
            alertTask.cancel();
            alertTask = null;
        }
    }

    // ── 토글 기능 ───────────────────────────────────────────

    /**
     * 특정 플레이어의 알림 수신 상태를 토글합니다.
     *
     * @param playerId 대상 플레이어 UUID
     * @return 토글 후 알림이 활성화되었으면 true
     */
    public boolean togglePlayerAlert(UUID playerId) {
        if (mutedPlayers.contains(playerId)) {
            mutedPlayers.remove(playerId);
            return true; // 알림 활성화됨
        } else {
            mutedPlayers.add(playerId);
            return false; // 알림 비활성화됨
        }
    }

    /** 특정 플레이어의 알림 수신 여부를 반환합니다. */
    public boolean isAlertEnabled(UUID playerId) {
        return !mutedPlayers.contains(playerId);
    }

    /** 전역 알림 상태를 토글합니다. */
    public boolean toggleGlobalAlert() {
        globalAlertEnabled = !globalAlertEnabled;
        return globalAlertEnabled;
    }

    /** 전역 알림 활성화 여부를 반환합니다. */
    public boolean isGlobalAlertEnabled() {
        return globalAlertEnabled;
    }
}
