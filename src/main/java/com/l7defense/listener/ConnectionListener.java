package com.l7defense.listener;

import com.l7defense.manager.SecurityManager;
import com.l7defense.util.IpUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 접속 단계에서의 L7 방어 리스너.
 *
 * <h3>v1.3 변경사항 (고급 휴리스틱 적용):</h3>
 * <ul>
 *   <li>호스트네임 검증: 숫자 IP 직접 접속 차단 방어 (포트스캐닝 봇 방어)</li>
 *   <li>Ping-before-Join: 핑 없이 들어온 접속은 재접속 검증(소프트 캡챠)으로 유도</li>
 *   <li>Rejoin Verification: 의심 유저를 즉시 차단하는 대신 지연 접속 검증 도입</li>
 *   <li>Anti-VPN: ip-api를 통한 데이터센터 IP 검증</li>
 * </ul>
 */
public final class ConnectionListener implements Listener {

    private final SecurityManager securityManager;
    private final Logger logger;
    private final boolean botFilterEnabled;
    private final boolean nameBypassAll;
    private final Set<String> whitelistedPlayers;
    private final Set<String> whitelistedIps;

    // v1.3 휴리스틱 설정
    private final boolean pingBeforeJoin;
    private final boolean pingOnlyUnderAttack;
    private final boolean rejoinVerification;
    private final boolean hostValidation;
    private final boolean hostBlockNumeric;
    private final List<String> allowedDomains;
    private final boolean vpnEnabled;
    private final boolean vpnOnlyUnderAttack;

    private static final Pattern NUMERIC_IP_PATTERN = Pattern.compile("^[0-9.]+(:[0-9]+)?$");

    private static final Pattern BOT_NAME_PATTERN = Pattern.compile(
            String.join("|",
                    "^(?:Bot|Slave|Attack)\\d{2,}$",
                    "^(?:Player|User|Test|Guest)\\d{5,}$",
                    "^[a-zA-Z]{1,2}\\d{6,}$",
                    "^\\d{4,}[a-zA-Z]{1,2}$",
                    "^[bcdfghjklmnpqrstvwxyz]{8,}$",
                    "^(?:[a-zA-Z]\\d){4,}$",
                    "^(?:[a-zA-Z0-9]{2}_){3,}"
            ),
            Pattern.CASE_INSENSITIVE
    );

    // ── 킥 메시지 ──────────────────────────────────────────
    private static final Component KICK_RATE_LIMITED = Component.text()
            .append(Component.text("⚠ 접속이 거부되었습니다\n\n", NamedTextColor.RED).decoration(TextDecoration.BOLD, true))
            .append(Component.text("단시간 내 너무 많은 접속을 시도하셨습니다.\n", NamedTextColor.YELLOW))
            .build();

    private static final Component KICK_BLOCKED_IP = Component.text()
            .append(Component.text("⚠ 접속이 차단되었습니다\n\n", NamedTextColor.DARK_RED).decoration(TextDecoration.BOLD, true))
            .append(Component.text("귀하의 IP는 보안 정책에 의해 일시 차단되었습니다.", NamedTextColor.RED))
            .build();

    private static final Component KICK_VERIFY_NEW = Component.text()
            .append(Component.text("🛡 봇 방지 보안 검증\n\n", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true))
            .append(Component.text("정상적인 플레이어인지 확인하기 위해\n", NamedTextColor.WHITE))
            .append(Component.text("약 3~5초 뒤에 다시 접속해주세요.", NamedTextColor.YELLOW))
            .build();

    private static final Component KICK_VERIFY_WAIT = Component.text()
            .append(Component.text("⚠ 재접속이 너무 빠릅니다\n\n", NamedTextColor.RED).decoration(TextDecoration.BOLD, true))
            .append(Component.text("봇 스크립트를 차단하기 위한 조치입니다.\n조금만 더 기다렸다가 다시 접속해주세요.", NamedTextColor.YELLOW))
            .build();

    private static final Component KICK_INVALID_HOST = Component.text()
            .append(Component.text("⚠ 올바르지 않은 접속 경로\n\n", NamedTextColor.RED).decoration(TextDecoration.BOLD, true))
            .append(Component.text("숫자 IP를 통한 직접 접속은 차단되어 있습니다.\n공식 서버 도메인으로 접속해주세요.", NamedTextColor.YELLOW))
            .build();

    public ConnectionListener(SecurityManager securityManager, Logger logger,
                              boolean botFilterEnabled, boolean nameBypassAll,
                              Set<String> whitelistedPlayers, Set<String> whitelistedIps,
                              boolean pingBeforeJoin, boolean pingOnlyUnderAttack,
                              boolean rejoinVerification, boolean hostValidation,
                              boolean hostBlockNumeric, List<String> allowedDomains,
                              boolean vpnEnabled, boolean vpnOnlyUnderAttack) {
        this.securityManager = securityManager;
        this.logger = logger;
        this.botFilterEnabled = botFilterEnabled;
        this.nameBypassAll = nameBypassAll;
        this.whitelistedPlayers = whitelistedPlayers;
        this.whitelistedIps = whitelistedIps;
        
        this.pingBeforeJoin = pingBeforeJoin;
        this.pingOnlyUnderAttack = pingOnlyUnderAttack;
        this.rejoinVerification = rejoinVerification;
        this.hostValidation = hostValidation;
        this.hostBlockNumeric = hostBlockNumeric;
        this.allowedDomains = allowedDomains;
        this.vpnEnabled = vpnEnabled;
        this.vpnOnlyUnderAttack = vpnOnlyUnderAttack;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        String ip = IpUtils.normalize(event.getAddress().getHostAddress());
        String playerName = event.getName();
        String hostname = event.getHostname(); // 클라이언트가 입력한 주소

        // ── 0. 화이트리스트 ───────────────────────────────
        if (whitelistedIps.contains(ip)) return;
        boolean nameWhitelisted = whitelistedPlayers.contains(playerName);
        if (nameWhitelisted && nameBypassAll) return;

        // ── 1. 하드 차단 검사 ─────────────────────────────
        if (securityManager.isBlocked(ip)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, KICK_BLOCKED_IP);
            return;
        }

        // ── 2. 호스트네임 검증 (포트스캐닝 봇 차단) ────────
        if (hostValidation && hostname != null) {
            boolean isNumeric = NUMERIC_IP_PATTERN.matcher(hostname).matches();
            if (isNumeric && hostBlockNumeric) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, KICK_INVALID_HOST);
                logger.fine("[L7] 숫자 IP 접속 차단: " + ip);
                return;
            }
            if (!allowedDomains.isEmpty() && !isNumeric) {
                boolean match = allowedDomains.stream().anyMatch(d -> hostname.toLowerCase().contains(d.toLowerCase()));
                if (!match) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, KICK_INVALID_HOST);
                    return;
                }
            }
        }

        // ── 3. 기본 빈도 제한 (Rate Limiting) ─────────────
        if (securityManager.recordAndCheckConnection(ip)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, KICK_RATE_LIMITED);
            securityManager.evaluateAttackStatus();
            return;
        }

        boolean isAttack = securityManager.isUnderAttack();
        boolean needsVerification = false;
        boolean forceGuiCaptcha = false;
        String verifyReason = "";

        // 이미 검증된 유저면 아래 휴리스틱 패스
        if (securityManager.isVerified(ip)) {
            needsVerification = false;
        } else {
            // ── 3.5 Geyser/Floodgate 베드락 우회 방어 (v1.7) ───────────
            if (securityManager.getModuleManager().isEnabled(com.l7defense.module.DefenseModule.GEYSER_BLOCK)) {
                // Floodgate로 접속한 유저는 UUID Version이 0이거나 이름 앞에 . 또는 * 이 붙습니다.
                boolean isBedrock = event.getUniqueId().version() == 0 || playerName.startsWith(".") || playerName.startsWith("*");
                if (isBedrock) {
                    needsVerification = true;
                    verifyReason = "Geyser/Floodgate 비인가 접근";
                    if (securityManager.getModuleManager().getPenalty(com.l7defense.module.DefenseModule.GEYSER_BLOCK) != com.l7defense.module.PenaltyAction.NOTIFY) {
                        securityManager.handlePreLoginViolation(event, com.l7defense.module.DefenseModule.GEYSER_BLOCK, verifyReason);
                        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
                    }
                }
            }

            // ── 4. Ping-before-Join 검사 ──────────────────
        if (securityManager.getModuleManager().isEnabled(com.l7defense.module.DefenseModule.PING)) {
            if (!securityManager.hasPingedRecently(ip)) {
                needsVerification = true;
                verifyReason = "Ping 기록 없음";
                // KICK 또는 BAN 설정 시 강제 처리
                if (securityManager.getModuleManager().getPenalty(com.l7defense.module.DefenseModule.PING) != com.l7defense.module.PenaltyAction.NOTIFY) {
                    securityManager.handlePreLoginViolation(event, com.l7defense.module.DefenseModule.PING, verifyReason);
                    if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
                }
            }
        }

        // ── 5. 봇 닉네임 필터 (v1.4 엔트로피 추가) ──────
        if (!needsVerification && securityManager.getModuleManager().isEnabled(com.l7defense.module.DefenseModule.ENTROPY) && !nameWhitelisted) {
            if (com.l7defense.util.NameEntropyChecker.isRandomBotName(playerName)) {
                securityManager.recordSuspiciousName(ip);
                needsVerification = true;
                forceGuiCaptcha = true; // 무작위 닉네임은 무조건 GUI 캡챠로 승격
                verifyReason = "무작위 봇/알트 닉네임 의심";
                if (securityManager.getModuleManager().getPenalty(com.l7defense.module.DefenseModule.ENTROPY) != com.l7defense.module.PenaltyAction.NOTIFY) {
                    securityManager.handlePreLoginViolation(event, com.l7defense.module.DefenseModule.ENTROPY, verifyReason);
                    if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
                }
            }
        }

        // ── 6. GeoIP & Anti-VPN 필터 (v1.4) ─────────────
        if (!needsVerification && securityManager.getModuleManager().isEnabled(com.l7defense.module.DefenseModule.VPN)) {
            if (securityManager.isRestrictedGeoIp(ip)) {
                needsVerification = true;
                forceGuiCaptcha = true;
                verifyReason = "제한된 국가/VPN/데이터센터";
                if (securityManager.getModuleManager().getPenalty(com.l7defense.module.DefenseModule.VPN) != com.l7defense.module.PenaltyAction.NOTIFY) {
                    securityManager.handlePreLoginViolation(event, com.l7defense.module.DefenseModule.VPN, verifyReason);
                    if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
                }
            }
        }
        
        } // end of !isVerified else block

        // ── 7. 검증 로직 라우팅 (v1.5/v1.6 Endgame Captcha Routing) ──
        if (needsVerification) {
            if (forceGuiCaptcha) {
                int captchaType = (int) (Math.random() * 4);
                if (captchaType == 0 && securityManager.getModuleManager().isEnabled(com.l7defense.module.DefenseModule.RP_CAPTCHA)) {
                    securityManager.flagForResourcePackCaptcha(ip);
                    logger.info("[L7] 캡챠 배정(RP) (" + verifyReason + "): " + playerName);
                } else if (captchaType == 1 && securityManager.getModuleManager().isEnabled(com.l7defense.module.DefenseModule.ENTITY_CAPTCHA)) {
                    securityManager.flagForEntityCaptcha(ip);
                    logger.info("[L7] 캡챠 배정(3D) (" + verifyReason + "): " + playerName);
                } else if (captchaType == 2 && securityManager.getModuleManager().isEnabled(com.l7defense.module.DefenseModule.MAP_CAPTCHA)) {
                    securityManager.flagForMapCaptcha(ip);
                    logger.info("[L7] 캡챠 배정(MAP) (" + verifyReason + "): " + playerName);
                } else if (securityManager.getModuleManager().isEnabled(com.l7defense.module.DefenseModule.GUI_CAPTCHA)) {
                    securityManager.flagForGuiCaptcha(ip);
                    logger.info("[L7] 캡챠 배정(GUI) (" + verifyReason + "): " + playerName);
                } else {
                    // 모두 비활성화되어있으면 통과
                    logger.info("[L7] 방어 모듈이 비활성화되어 통과 (" + verifyReason + "): " + playerName);
                }
                return; // 캡챠 처리를 위해 인게임으로 일단 들여보냄
            }

            // 기본 소프트 캡챠 (Rejoin Verification)
            if (rejoinVerification) {
                SecurityManager.VerificationResult result = securityManager.checkVerification(ip);
                switch (result) {
                    case PASSED:
                        logger.fine("[L7] 재접속 검증 통과: " + playerName + " (" + ip + ")");
                        break;
                    case WAITING:
                        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, KICK_VERIFY_WAIT);
                        logger.info("[L7] 빠른 재접속(봇 스크립트 의심): " + ip);
                        break;
                    case EXPIRED:
                    case NEW:
                    default:
                        securityManager.startVerification(ip);
                        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, KICK_VERIFY_NEW);
                        logger.info("[L7] 재접속 검증 요구 (" + verifyReason + "): " + playerName + " (" + ip + ")");
                        break;
                }
                return;
            }
        }

        logger.fine("[L7] 접속 허용: " + playerName + " (" + ip + ")");
    }
}
