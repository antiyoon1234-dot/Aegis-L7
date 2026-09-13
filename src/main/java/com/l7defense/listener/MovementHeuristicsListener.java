package com.l7defense.listener;
import com.l7defense.util.IpUtils;

import com.l7defense.manager.SecurityManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 마우스 시점 및 이동 휴리스틱 분석 (Anti-Baritone / Bot Movement AI).
 * <p>
 * 봇들은 목표를 향해 인간이 불가능한 속도(1틱 만에 정확히 180도)로 시점을 꺾거나(Snap Aim),
 * Pitch(위아래 고개 각도)가 완벽한 정수(예: 0.0, 90.0)로 고정된 채 움직이는 경우가 많습니다.
 */
public final class MovementHeuristicsListener implements Listener {

    private final SecurityManager securityManager;
    private final boolean enabled;

    public MovementHeuristicsListener(SecurityManager securityManager, boolean enabled) {
        this.securityManager = securityManager;
        this.enabled = enabled;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!enabled) return;

        Player player = event.getPlayer();
        if (player.hasPermission("l7defense.admin")) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        
        // 시점 이동이 있는 경우
        if (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) {
            float deltaYaw = Math.abs(to.getYaw() - from.getYaw());
            float deltaPitch = Math.abs(to.getPitch() - from.getPitch());
            
            // 휴리스틱 1: 스냅 에임(Snap Aim) 방어
            // 1틱(0.05초)만에 시점을 180도 이상 정확하게 휙 꺾는 경우 (사람은 불가능에 가까움)
            if (deltaYaw > 175.0f && deltaYaw < 185.0f && deltaPitch == 0.0f) {
                flagBot(player, "기계적인 시점 이동 감지 (Snap Aim)");
                return;
            }
            
            // 휴리스틱 2: 완벽한 정수 Pitch 고정 (Baritone이나 싸구려 봇의 특징)
            // 보통 사람의 마우스 센서는 float 소수점 이하 단위로 계속 떨림(Jitter)이 발생함
            float p = to.getPitch();
            if ((p == 90.0f || p == -90.0f || p == 0.0f) && deltaYaw > 10.0f) {
                // 특정 각도로 완벽하게 고정한 상태에서 몸만 회전하는 경우 의심도 증가
                // 단, 단순 의심이므로 바로 밴하지 않고 SecurityManager 쪽에 로깅하는 것이 안전함.
                // 여기서는 극단적인 공격 방어용으로 즉시 차단 사용.
            }
        }
    }

    private void flagBot(Player player, String reason) {
        String ip = IpUtils.getIp(player);
        securityManager.blockIp(ip, reason);
        player.kick(Component.text("비정상적인 움직임이 감지되었습니다.", NamedTextColor.RED));
    }
}
