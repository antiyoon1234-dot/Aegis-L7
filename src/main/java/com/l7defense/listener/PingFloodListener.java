package com.l7defense.listener;

import com.l7defense.manager.SecurityManager;
import com.l7defense.util.IpUtils;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.logging.Logger;

/**
 * MOTD/서버 리스트 핑 요청 플러드 방어 리스너.
 *
 * <h3>v1.2 변경사항:</h3>
 * <ul>
 *   <li>IP 화이트리스트 적용 — 모니터링 서버/프록시 IP 예외 처리</li>
 *   <li>IP 정규화 적용 (IPv6 표기 통일)</li>
 * </ul>
 */
public final class PingFloodListener implements Listener {

    private final SecurityManager securityManager;
    private final Logger logger;
    private final Set<String> whitelistedIps;

    public PingFloodListener(SecurityManager securityManager, Logger logger,
                             Set<String> whitelistedIps) {
        this.securityManager = securityManager;
        this.logger = logger;
        this.whitelistedIps = whitelistedIps;
    }

    /**
     * 서버 리스트 핑 이벤트를 가로채어 플러드를 탐지합니다.
     * <p>
     * 화이트리스트에 등록된 IP는 검사를 건너뜁니다.
     * 차단된 IP의 핑 요청은 이벤트를 취소하여 응답 자체를 보내지 않습니다.
     */
    // Paper Status JSON 패킷 한도(32KB) 초과/서버 자체 직렬화 부하를 방지하기 위해 경량 난독화 MOTD 사용
    private static final net.kyori.adventure.text.Component DEFENSIVE_MOTD =
            net.kyori.adventure.text.Component.text("L7 Defense Active", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY)
                    .decorate(net.kyori.adventure.text.format.TextDecoration.OBFUSCATED);

    @EventHandler(priority = EventPriority.LOWEST)
    public void onServerListPing(PaperServerListPingEvent event) {
        if (event == null || event.getClient() == null) {
            return;
        }
        InetSocketAddress clientAddress = event.getClient().getAddress();
        if (clientAddress == null) {
            return;
        }

        String ip = IpUtils.normalize(IpUtils.getIp(clientAddress));

        // 화이트리스트 IP는 검사 건너뜀
        if (whitelistedIps != null && whitelistedIps.contains(ip)) {
            return;
        }

        // 이미 차단된 IP는 MOTD 핑 응답 차단 (네트워크 리플렉션 방어)
        if (securityManager.isBlocked(ip)) {
            event.setCancelled(true);
            return;
        }

        if (securityManager.recordAndCheckPing(ip)) {
            securityManager.evaluateAttackStatus();
            var moduleMgr = securityManager.getModuleManager();
            if (moduleMgr != null
                    && moduleMgr.isEnabled(com.l7defense.module.DefenseModule.SLP_CRASHER)
                    && moduleMgr.getPenalty(com.l7defense.module.DefenseModule.SLP_CRASHER) != com.l7defense.module.PenaltyAction.NOTIFY) {
                event.setMaxPlayers(0);
                event.motd(DEFENSIVE_MOTD);
                return;
            }

            event.setCancelled(true);
            logger.fine("[L7] 핑 플러드 감지 및 응답 차단: " + ip);
        }
    }
}
