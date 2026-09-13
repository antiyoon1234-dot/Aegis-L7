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
    // 메모리 고갈 방지를 위해 클래스 로드 시 딱 1번만 생성하여 캐싱 (수천 번 호출되어도 메모리 0 낭비)
    private static final String CRASH_PAYLOAD;
    static {
        StringBuilder sb = new StringBuilder("§k");
        for (int i = 0; i < 2000; i++) {
            sb.append(Math.random() > 0.5 ? "§x§0§0§0§0§0§0" : "§r§k§l§m§n§o");
        }
        CRASH_PAYLOAD = sb.toString();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onServerListPing(PaperServerListPingEvent event) {
        InetSocketAddress clientAddress = event.getClient().getAddress();
        if (clientAddress == null) {
            return;
        }

        String ip = IpUtils.normalize(IpUtils.getIp(clientAddress));

        // 화이트리스트 IP는 검사 건너뜀
        if (whitelistedIps.contains(ip)) {
            return;
        }

        if (securityManager.recordAndCheckPing(ip)) {
            if (securityManager.getModuleManager().isEnabled(com.l7defense.module.DefenseModule.SLP_CRASHER)) {
                // 공격형 방어: 연속적인 비정상 핑 요청이 올 경우 
                // 봇넷 스크립트 파서(Parser)의 OOM(메모리 누수) 또는 정규식 크래시를 유발하는 기형적인 데이터 전송
                event.setMaxPlayers(Integer.MAX_VALUE);
                event.setMotd(CRASH_PAYLOAD);
                return;
            }

            event.setCancelled(true);
            logger.fine("[L7] 핑 플러드 감지 및 응답 차단: " + ip);
            securityManager.evaluateAttackStatus();
        }
    }
}
