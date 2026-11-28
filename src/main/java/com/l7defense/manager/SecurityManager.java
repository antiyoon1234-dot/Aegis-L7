package com.l7defense.manager;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.l7defense.util.FirewallManager;
import com.l7defense.util.GeoIpManager;
import com.l7defense.util.IpUtils;
import com.l7defense.util.RedisManager;

import java.util.concurrent.TimeUnit;
import java.util.UUID;
import com.l7defense.module.DefenseModule;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 보안 상태 및 통계를 중앙 관리하는 스레드-안전 매니저.
 *
 * <h3>v1.3 변경사항:</h3>
 * <ul>
 *   <li>Ping-before-join 캐시 추가 ({@code recentPings})</li>
 *   <li>재접속 검증 (Soft Captcha) 상태 캐시 추가</li>
 *   <li>VPN 캐시 및 방화벽 매니저 통합</li>
 * </ul>
 */
public class SecurityManager {

    // ── 설정값 (Config) ─────────────────────────────────────
    private final int maxConnectionsPerWindow;
    private final int connectionWindowSeconds;
    private final int maxPingsPerWindow;
    private final int pingWindowSeconds;
    private final int blockDurationMinutes;
    private final int attackThreshold;
    private final int denialThreshold;
    private final int attackWindowSeconds;
    private final int suspiciousNameThreshold;
    private final int rejoinMinDelay;
    private final int rejoinMaxDelay;

    // ── 매니저/유틸 ─────────────────────────────────────────
    private FirewallManager firewallManager;
    private GeoIpManager geoIpManager;
    private RedisManager redisManager;
    private com.l7defense.module.ModuleConfigManager moduleManager;
    private AlertManager alertManager;

    // ── Caffeine Cache 인스턴스 ─────────────────────────────
    private final Cache<String, AtomicInteger> connectionAttempts;
    private final Cache<String, Boolean> blockedIps;
    private final Cache<String, AtomicInteger> pingAttempts;
    private final Cache<Long, String> recentBlockEvents;
    private final Cache<Long, Boolean> recentDeniedEvents;
    private final Cache<Long, Boolean> recentAttemptEvents;
    private final Cache<String, AtomicInteger> suspiciousNameCounter;
    private final Cache<String, Boolean> captchaRetryGranted;
    private final Cache<VerificationKey, Long> captchaRetries = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES).maximumSize(10_000).build();

    // ── v1.3 / v1.4 신규 캐시 ──────────────────────────────────────
    private final Cache<String, Boolean> recentPings;
    private record VerificationKey(String ip, UUID playerId) {}
    private final Cache<VerificationKey, Long> pendingVerification;
    private final CaptchaStates captchas = new CaptchaStates();
    private PlayerSessions sessions;
    private volatile boolean closed;
    public void setSessions(PlayerSessions sessions) { this.sessions = sessions; }
    public PlayerSessions getSessions() { return sessions; }
    public CaptchaStates getCaptchas() { return captchas; }
    private final Cache<UUID, Boolean> verifiedPlayers;

    /** 인게임 GUI 캡챠를 통과해야 하는 IP 목록 (v1.4) */


    // ── 고유 ID 생성기 & 통계 ──────────────────────────────
    private final AtomicLong blockEventId = new AtomicLong(0);
    private final AtomicLong deniedEventId = new AtomicLong(0);
    private final AtomicLong attemptEventId = new AtomicLong(0);
    private final LongAdder lifetimeBlockedCount = new LongAdder();

    private volatile boolean underAttack = false;

    // 인증 결과 열거형
    public enum VerificationResult {
        PASSED, WAITING, EXPIRED, NEW
    }

    private java.util.function.Consumer<org.bukkit.entity.Player> guiCaptchaTrigger;
    private java.util.function.Consumer<org.bukkit.entity.Player> entityCaptchaTrigger;
    private java.util.function.Consumer<org.bukkit.entity.Player> mapCaptchaTrigger;
    private java.util.function.Consumer<org.bukkit.entity.Player> rpCaptchaTrigger;

    public void setGuiCaptchaTrigger(java.util.function.Consumer<org.bukkit.entity.Player> trigger) { this.guiCaptchaTrigger = trigger; }
    public void setEntityCaptchaTrigger(java.util.function.Consumer<org.bukkit.entity.Player> trigger) { this.entityCaptchaTrigger = trigger; }
    public void setMapCaptchaTrigger(java.util.function.Consumer<org.bukkit.entity.Player> trigger) { this.mapCaptchaTrigger = trigger; }
    public void setRpCaptchaTrigger(java.util.function.Consumer<org.bukkit.entity.Player> trigger) { this.rpCaptchaTrigger = trigger; }
    public void setAlertManager(AlertManager alertManager) { this.alertManager = alertManager; }

    public void challengeHeuristic(org.bukkit.entity.Player player, DefenseModule module, String reason) {
        if (closed || moduleManager == null || !moduleManager.isEnabled(module)) return;
        if (moduleManager.getPenalty(module) == com.l7defense.module.PenaltyAction.NOTIFY) {
            handleViolation(player, module, reason);
        } else if (guiCaptchaTrigger != null && moduleManager.isEnabled(DefenseModule.GUI_CAPTCHA)) {
            if (captchas.get(player.getUniqueId()) != null) return;
            captchas.start(player.getUniqueId(), DefenseModule.GUI_CAPTCHA);
            guiCaptchaTrigger.accept(player);
        } else {
            handleViolation(player, module, reason);
        }
    }

    public SecurityManager(int maxConnections, int connectionWindowSec,
                           int maxPings, int pingWindowSec,
                           int blockDurationMin, int maxCacheSize,
                           int attackThreshold, int denialThreshold,
                           int attackWindowSec, int suspiciousNameThreshold,
                           int rejoinMinDelay, int rejoinMaxDelay) {
        if (maxConnections < 1 || connectionWindowSec < 1 || maxPings < 1 || pingWindowSec < 1
                || blockDurationMin < 1 || maxCacheSize < 1 || attackThreshold < 1 || denialThreshold < 1
                || attackWindowSec < 1 || suspiciousNameThreshold < 1 || rejoinMinDelay < 0
                || rejoinMaxDelay < Math.max(1, rejoinMinDelay)) {
            throw new IllegalArgumentException("보안 한도/시간 설정이 유효하지 않습니다.");
        }
        this.maxConnectionsPerWindow = maxConnections;
        this.connectionWindowSeconds = connectionWindowSec;
        this.maxPingsPerWindow = maxPings;
        this.pingWindowSeconds = pingWindowSec;
        this.blockDurationMinutes = blockDurationMin;
        this.attackThreshold = attackThreshold;
        this.denialThreshold = denialThreshold;
        this.attackWindowSeconds = attackWindowSec;
        this.suspiciousNameThreshold = suspiciousNameThreshold;
        this.rejoinMinDelay = rejoinMinDelay;
        this.rejoinMaxDelay = rejoinMaxDelay;

        this.connectionAttempts = Caffeine.newBuilder().expireAfterWrite(connectionWindowSec, TimeUnit.SECONDS).maximumSize(10_000).build();
        this.blockedIps = Caffeine.newBuilder().expireAfterWrite(blockDurationMin, TimeUnit.MINUTES).maximumSize(maxCacheSize).build();
        this.pingAttempts = Caffeine.newBuilder().expireAfterWrite(pingWindowSec, TimeUnit.SECONDS).maximumSize(10_000).build();
        this.recentBlockEvents = Caffeine.newBuilder().expireAfterWrite(attackWindowSec, TimeUnit.SECONDS).maximumSize(100_000).build();
        this.recentDeniedEvents = Caffeine.newBuilder().expireAfterWrite(attackWindowSec, TimeUnit.SECONDS).maximumSize(100_000).build();
        this.recentAttemptEvents = Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(100_000).build();
        this.suspiciousNameCounter = Caffeine.newBuilder().expireAfterWrite(connectionWindowSec, TimeUnit.SECONDS).maximumSize(10_000).build();
        this.captchaRetryGranted = Caffeine.newBuilder().expireAfterWrite(connectionWindowSec, TimeUnit.SECONDS).maximumSize(10_000).build();

        this.recentPings = Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(50_000).build();
        this.pendingVerification = Caffeine.newBuilder().expireAfterWrite((long) rejoinMaxDelay + 5, TimeUnit.SECONDS).maximumSize(10_000).build();
        this.verifiedPlayers = Caffeine.newBuilder().expireAfterWrite(12, TimeUnit.HOURS).maximumSize(50_000).build();

        // v1.4

    }

    public void setModules(FirewallManager firewallManager, GeoIpManager geoIpManager) {
        this.firewallManager = firewallManager;
        this.geoIpManager = geoIpManager;
    }

    public void setRedisManager(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    public void setModuleManager(com.l7defense.module.ModuleConfigManager moduleManager) {
        this.moduleManager = moduleManager;
    }

    public com.l7defense.module.ModuleConfigManager getModuleManager() {
        return this.moduleManager;
    }

    /**
     * 중앙 집중식 패널티 핸들러 (v1.6)
     * BAN, KICK, NOTIFY 설정에 따라 알아서 처리합니다.
     */
    public void handleViolation(org.bukkit.entity.Player player, com.l7defense.module.DefenseModule module, String reason) {
        if (closed || player == null || module == null) return;
        if (org.bukkit.Bukkit.isPrimaryThread()) {
            applyViolation(player, module, reason);
        } else if (sessions != null) {
            sessions.submit(player, "violation-" + module.name(), () -> applyViolation(player, module, reason));
        } else {
            org.bukkit.plugin.Plugin pl = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
            if (pl != null && pl.isEnabled()) {
                org.bukkit.Bukkit.getScheduler().runTask(pl, () -> applyViolation(player, module, reason));
            } else {
                applyViolation(player, module, reason);
            }
        }
    }

    private void applyViolation(org.bukkit.entity.Player player, DefenseModule module, String reason) {
        if (closed || moduleManager == null || !moduleManager.isEnabled(module)) return;
        var action = moduleManager.getPenalty(module);
        String ip = IpUtils.getIp(player);
        // 비동기 이벤트(AsyncPlayerChatEvent 등)에서 호출될 수 있으므로,
        // 순수 Bukkit API (Kick, SendMessage)는 반드시 메인 스레드(Sync)에서 실행
        if (action == com.l7defense.module.PenaltyAction.BAN) {
            blockIp(ip, reason);
            player.kick(net.kyori.adventure.text.Component.text("서버 보안 정책에 의해 차단되었습니다.\n\n사유: " + reason, net.kyori.adventure.text.format.NamedTextColor.RED));
        } else if (action == com.l7defense.module.PenaltyAction.KICK) {
            player.kick(net.kyori.adventure.text.Component.text("비정상적인 접근/행동으로 강제 퇴장되었습니다.\n\n사유: " + reason, net.kyori.adventure.text.format.NamedTextColor.RED));
        } else {
            org.bukkit.Bukkit.getLogger().warning("[L7-Notify] " + player.getName() + "(" + ip + ") - " + reason);
            var playerMsg = net.kyori.adventure.text.Component.text("[L7 주의] ", net.kyori.adventure.text.format.NamedTextColor.YELLOW)
                    .append(net.kyori.adventure.text.Component.text("비정상적인 활동이 감지되었습니다: ", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                    .append(net.kyori.adventure.text.Component.text(reason, net.kyori.adventure.text.format.NamedTextColor.WHITE));
            player.sendMessage(playerMsg);
            var adminMsg = net.kyori.adventure.text.Component.text("[L7 경고] ", net.kyori.adventure.text.format.NamedTextColor.GOLD)
                    .append(net.kyori.adventure.text.Component.text(player.getName() + ": ", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                    .append(net.kyori.adventure.text.Component.text(reason, net.kyori.adventure.text.format.NamedTextColor.WHITE));
            for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (p.getUniqueId().equals(player.getUniqueId())) continue; // 대상자가 관리자인 경우 중복 알림 방지
                if (p.isOp() || p.hasPermission("l7defense.admin") || p.hasPermission("l7defense.alerts")) {
                    if (alertManager == null || alertManager.isAlertEnabled(p.getUniqueId())) {
                        p.sendMessage(adminMsg);
                    }
                }
            }
        }
    }

    /**
     * Pre-Login 환경용 패널티 핸들러 (v1.6)
     * Player 객체가 생성되기 전이므로 AsyncPlayerPreLoginEvent를 통해 제어합니다.
     */
    public void handlePreLoginViolation(org.bukkit.event.player.AsyncPlayerPreLoginEvent event, com.l7defense.module.DefenseModule module, String reason) {
        if (closed || event == null || moduleManager == null || !moduleManager.isEnabled(module)) return;
        if (event.getAddress() == null) return;

        com.l7defense.module.PenaltyAction action = moduleManager.getPenalty(module);
        String ip = IpUtils.normalize(event.getAddress().getHostAddress());
        String name = event.getName() != null ? event.getName() : "unknown";

        if (action == com.l7defense.module.PenaltyAction.BAN) {
            blockIp(ip, reason);
            event.disallow(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_BANNED, net.kyori.adventure.text.Component.text("차단되었습니다: " + reason, net.kyori.adventure.text.format.NamedTextColor.RED));
        } else if (action == com.l7defense.module.PenaltyAction.KICK) {
            event.disallow(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_OTHER, net.kyori.adventure.text.Component.text("접속 거부: " + reason, net.kyori.adventure.text.format.NamedTextColor.RED));
        } else if (action == com.l7defense.module.PenaltyAction.NOTIFY) {
            // 통과시킴
            org.bukkit.Bukkit.getLogger().warning("[L7-Notify] " + name + "(" + ip + ") 의심 감지 - " + reason);
        }
    }

    public boolean recordAndCheckConnection(String rawIp) {
        String ip = IpUtils.normalize(rawIp);
        if (isBlockedNormalized(ip)) {
            recordAttempt();
            recordDeniedEvent();
            return true;
        }

        AtomicInteger counter = connectionAttempts.get(ip, k -> new AtomicInteger(0));
        int attempts = counter.incrementAndGet();
        recordAttempt();

        if (attempts > maxConnectionsPerWindow) {
            blockIpNormalized(ip, "Rate Limit 초과");
            return true;
        }
        return false;
    }

    public void offerCaptchaRetry(org.bukkit.entity.Player player) {
        if (player == null) return;
        String ip = IpUtils.getIp(player);
        UUID id = player.getUniqueId();
        if (captchaRetryGranted.asMap().putIfAbsent(ip, Boolean.TRUE) == null) {
            captchaRetries.put(new VerificationKey(ip, id), System.nanoTime());
        }
    }

    public boolean recordAndCheckConnection(String rawIp, UUID id) {
        String ip = IpUtils.normalize(rawIp);
        if (isBlocked(ip)) return recordAndCheckConnection(ip);
        VerificationKey key = new VerificationKey(ip, id);
        Long issued = captchaRetries.getIfPresent(key);
        if (issued != null && System.nanoTime() - issued >= TimeUnit.SECONDS.toNanos(rejoinMinDelay)
                && captchaRetries.asMap().remove(key, issued)) {
            recordAttempt();
            return false;
        }
        return recordAndCheckConnection(ip);
    }

    public boolean recordAndCheckPing(String rawIp) {
        String ip = IpUtils.normalize(rawIp);
        if (isBlockedNormalized(ip)) {
            recordDeniedEvent();
            return true;
        }
        // Ping 기록 (Ping-before-join 검증용)
        recentPings.put(ip, Boolean.TRUE);

        AtomicInteger counter = pingAttempts.get(ip, k -> new AtomicInteger(0));
        if (counter.incrementAndGet() > maxPingsPerWindow) {
            blockIpNormalized(ip, "핑 플러드");
            return true;
        }
        return false;
    }

    public boolean hasPingedRecently(String rawIp) {
        return recentPings.getIfPresent(IpUtils.normalize(rawIp)) != null;
    }

    // ── 2. 봇 닉네임 및 차단 관리 ───────────────────────────

    public boolean recordSuspiciousName(String rawIp) {
        String ip = IpUtils.normalize(rawIp);
        AtomicInteger counter = suspiciousNameCounter.get(ip, k -> new AtomicInteger(0));
        if (counter.incrementAndGet() >= suspiciousNameThreshold && moduleManager != null
                && moduleManager.getPenalty(DefenseModule.ENTROPY) == com.l7defense.module.PenaltyAction.BAN) {
            blockIpNormalized(ip, "반복 봇 닉네임");
            return true;
        }
        return false;
    }

    public void blockIp(String rawIp, String reason) {
        blockIpNormalized(IpUtils.normalize(rawIp), reason);
    }

    private void blockIpNormalized(String normalizedIp, String reason) {
        if (closed || !IpUtils.isLiteral(normalizedIp)) return;
        Boolean previous = blockedIps.asMap().putIfAbsent(normalizedIp, Boolean.TRUE);
        if (previous == null) {
            if (redisManager != null) redisManager.publishBlock(normalizedIp);
            lifetimeBlockedCount.increment();
            recentBlockEvents.put(blockEventId.incrementAndGet(), normalizedIp);

            // 방화벽(iptables) 연동 차단
            if (firewallManager != null) {
                firewallManager.executeBlock(normalizedIp, TimeUnit.MINUTES.toMillis(blockDurationMinutes));
            }
        }
    }

    public void receiveRemoteBlock(String ip) {
        if (closed || !IpUtils.isLiteral(ip)) return;
        ip = IpUtils.normalize(ip);
        if (blockedIps.asMap().putIfAbsent(ip, Boolean.TRUE) == null) {
            lifetimeBlockedCount.increment();
            recentBlockEvents.put(blockEventId.incrementAndGet(), ip);
            if (firewallManager != null) firewallManager.executeBlock(ip, TimeUnit.MINUTES.toMillis(blockDurationMinutes));
        }
    }

    public void receiveRemoteUnblock(String rawIp) {
        if (closed || !IpUtils.isLiteral(rawIp)) return;
        String ip = IpUtils.normalize(rawIp);
        blockedIps.invalidate(ip);
        connectionAttempts.invalidate(ip);
        pingAttempts.invalidate(ip);
        suspiciousNameCounter.invalidate(ip);
        if (firewallManager != null) firewallManager.executeUnblock(ip);
    }

    public boolean isBlocked(String rawIp) {
        return isBlockedNormalized(IpUtils.normalize(rawIp));
    }

    private boolean isBlockedNormalized(String normalizedIp) {
        return blockedIps.getIfPresent(normalizedIp) != null;
    }

    public boolean unblockIp(String rawIp) {
        String ip = IpUtils.normalize(rawIp);
        boolean wasBlocked = isBlockedNormalized(ip);
        blockedIps.invalidate(ip);
        connectionAttempts.invalidate(ip);
        pingAttempts.invalidate(ip);
        suspiciousNameCounter.invalidate(ip);

        if (wasBlocked && redisManager != null) redisManager.publishUnblock(ip);
        if (firewallManager != null) {
            firewallManager.executeUnblock(ip);
        }
        return wasBlocked;
    }

    // ── 3. 재접속 검증 (Soft Captcha) ───────────────────────

    public boolean isVerified(UUID id) { return verifiedPlayers.getIfPresent(id) != null; }

    public void startVerification(String rawIp, UUID id) {
        pendingVerification.asMap().putIfAbsent(new VerificationKey(IpUtils.normalize(rawIp), id), System.nanoTime());
    }

    public VerificationResult checkVerification(String rawIp, UUID id) {
        VerificationKey key = new VerificationKey(IpUtils.normalize(rawIp), id);
        java.util.concurrent.atomic.AtomicReference<VerificationResult> result =
                new java.util.concurrent.atomic.AtomicReference<>(VerificationResult.NEW);
        pendingVerification.asMap().computeIfPresent(key, (k, start) -> {
            long elapsed = System.nanoTime() - start;
            if (elapsed < TimeUnit.SECONDS.toNanos(rejoinMinDelay)) {
                result.set(VerificationResult.WAITING); // 너무 빠름 (봇 스크립트 특징)
                return start;
            }
            if (elapsed > TimeUnit.SECONDS.toNanos(rejoinMaxDelay)) {
                result.set(VerificationResult.EXPIRED); // 늦음
            } else {
                // 검증 성공: 허용된 단 한 번의 재접속만 기본 접속 횟수에서 제외합니다.
                result.set(VerificationResult.PASSED);
                verifiedPlayers.put(id, Boolean.TRUE);
            }
            return null;
        });
        return result.get();
    }

    // ── 4. VPN 및 GeoIP 검사 (v1.4 통합) ───────────────────

    public boolean isRestrictedGeoIp(String rawIp, boolean checkVpn, boolean checkForeign) {
        if (geoIpManager == null) return false;
        return geoIpManager.isRestricted(IpUtils.normalize(rawIp), checkVpn, checkForeign);
    }

    // ── 4-2. 캡챠 상태 플래그 (v1.4 / v1.5) ────────────────────────────────

    /** 인게임 GUI 캡챠를 통과해야 하는 플레이어 목록 (v1.4) */
    // (v1.5) Resource Pack Captcha 플래그
    // (v1.5) 3D Entity Captcha 플래그
    // (v1.6) Map OCR Captcha 플래그
    public void flagForGuiCaptcha(UUID id) { captchas.flag(id, DefenseModule.GUI_CAPTCHA); }
    public boolean needsGuiCaptcha(UUID id) { return captchas.needs(id, DefenseModule.GUI_CAPTCHA); }
    public void passGuiCaptcha(org.bukkit.entity.Player player) {
        if (captchas.pass(player.getUniqueId(), DefenseModule.GUI_CAPTCHA)) verifiedPlayers.put(player.getUniqueId(), Boolean.TRUE);
    }

    public void flagForResourcePackCaptcha(UUID id) { captchas.flag(id, DefenseModule.RP_CAPTCHA); }
    public boolean needsResourcePackCaptcha(UUID id) { return captchas.needs(id, DefenseModule.RP_CAPTCHA); }
    public void passResourcePackCaptcha(org.bukkit.entity.Player player) {
        if (captchas.pass(player.getUniqueId(), DefenseModule.RP_CAPTCHA)) verifiedPlayers.put(player.getUniqueId(), Boolean.TRUE);
    }

    public void flagForEntityCaptcha(UUID id) { captchas.flag(id, DefenseModule.ENTITY_CAPTCHA); }
    public boolean needsEntityCaptcha(UUID id) { return captchas.needs(id, DefenseModule.ENTITY_CAPTCHA); }
    public void passEntityCaptcha(org.bukkit.entity.Player player) {
        if (captchas.pass(player.getUniqueId(), DefenseModule.ENTITY_CAPTCHA)) verifiedPlayers.put(player.getUniqueId(), Boolean.TRUE);
    }

    public void flagForMapCaptcha(UUID id) { captchas.flag(id, DefenseModule.MAP_CAPTCHA); }
    public boolean needsMapCaptcha(UUID id) { return captchas.needs(id, DefenseModule.MAP_CAPTCHA); }
    public void passMapCaptcha(org.bukkit.entity.Player player) {
        if (captchas.pass(player.getUniqueId(), DefenseModule.MAP_CAPTCHA)) verifiedPlayers.put(player.getUniqueId(), Boolean.TRUE);
    }

    // ── 5. 이벤트 및 공격 상태 평가 ─────────────────────────

    private void recordAttempt() {
        recentAttemptEvents.put(attemptEventId.incrementAndGet(), Boolean.TRUE);
    }

    public void recordVerifiedReconnect() { recordAttempt(); }
    public void recordDeniedConnection() { recordAttempt(); recordDeniedEvent(); }

    private void recordDeniedEvent() {
        recentDeniedEvents.put(deniedEventId.incrementAndGet(), Boolean.TRUE);
    }

    public void evaluateAttackStatus() {
        recentBlockEvents.cleanUp();
        recentDeniedEvents.cleanUp();
        long recentBlocks = recentBlockEvents.estimatedSize();
        long recentDenials = recentDeniedEvents.estimatedSize();

        underAttack = recentBlocks >= attackThreshold || recentDenials >= denialThreshold;
    }

    // ── Getter / Stats ───────────────────────────────────────

    public boolean isUnderAttack() { return underAttack; }
    public long getLifetimeBlockedCount() { return lifetimeBlockedCount.sum(); }
    public long getRecentAttemptsCount() { recentAttemptEvents.cleanUp(); return recentAttemptEvents.estimatedSize(); }
    public long getRecentBlockCount() { recentBlockEvents.cleanUp(); return recentBlockEvents.estimatedSize(); }
    public long getRecentDeniedCount() { recentDeniedEvents.cleanUp(); return recentDeniedEvents.estimatedSize(); }
    public long getActiveBlockedCount() { blockedIps.cleanUp(); return blockedIps.estimatedSize(); }

    public void resetStats() {
        lifetimeBlockedCount.reset();
        connectionAttempts.invalidateAll();
        for (String ip : blockedIps.asMap().keySet()) {
            if (firewallManager != null) firewallManager.executeUnblock(ip);
        }
        blockedIps.invalidateAll();
        pingAttempts.invalidateAll();
        recentBlockEvents.invalidateAll();
        recentDeniedEvents.invalidateAll();
        recentAttemptEvents.invalidateAll();
        suspiciousNameCounter.invalidateAll();
        recentPings.invalidateAll();
        pendingVerification.invalidateAll();
        verifiedPlayers.invalidateAll();
        captchas.clear();
        captchaRetryGranted.invalidateAll();
        captchaRetries.invalidateAll();
        underAttack = false;
    }

    public int getMaxConnectionsPerWindow() { return maxConnectionsPerWindow; }
    public int getConnectionWindowSeconds() { return connectionWindowSeconds; }
    public int getMaxPingsPerWindow() { return maxPingsPerWindow; }
    public int getPingWindowSeconds() { return pingWindowSeconds; }
    // 30초 후 확인 작업은 각 캡챠 리스너가 발급/재발급/종료 시 함께 관리합니다.
    // 강제 밴 처리 여부는 모듈의 현재 처벌 설정을 따르며 설정 파일을 변경하지 않습니다.
    public void shutdown() { closed = true; captchas.clear(); }
    public boolean isActive() { return !closed; }

    public int getBlockDurationMinutes() { return blockDurationMinutes; }

    public void simulateTest(org.bukkit.entity.Player player, com.l7defense.module.DefenseModule module) {
        UUID id = player.getUniqueId();
        com.l7defense.module.PenaltyAction penalty = getModuleManager().getPenalty(module);

                org.bukkit.plugin.Plugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
        if (plugin == null) return;
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (closed || !player.isOnline()) return;
            org.bukkit.Bukkit.getLogger().warning("[L7-Notify] " + player.getName() + " - " + module.getId() + " 테스트");
            for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (p.isOp()) {
                    p.sendMessage(net.kyori.adventure.text.Component.text(
                        "[L7 테스트] " + player.getName() + " 이(가) " + module.getDescription() + " 발동!",
                        net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
                }
            }
            org.bukkit.Bukkit.getLogger().info("[L7-Debug] Scheduler Started for: " + module.getId());
            switch (module) {
                case GUI_CAPTCHA -> {
                    captchas.start(id, module);
                    player.sendMessage(net.kyori.adventure.text.Component.text("[L7] GUI 캡챠 발동!", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                    org.bukkit.Bukkit.getLogger().info("[L7-Debug] guiCaptchaTrigger is " + (guiCaptchaTrigger != null ? "READY" : "NULL"));
                    if (guiCaptchaTrigger != null) { guiCaptchaTrigger.accept(player); }
                }
                case ENTITY_CAPTCHA -> {
                    captchas.start(id, module);
                    player.sendMessage(net.kyori.adventure.text.Component.text("[L7] 3D Entity 캡챠 발동!", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                    org.bukkit.Bukkit.getLogger().info("[L7-Debug] entityCaptchaTrigger is " + (entityCaptchaTrigger != null ? "READY" : "NULL"));
                    if (entityCaptchaTrigger != null) { entityCaptchaTrigger.accept(player); }
                }
                case RP_CAPTCHA -> {
                    if (rpCaptchaTrigger != null) {
                        captchas.start(id, module);
                        player.sendMessage(net.kyori.adventure.text.Component.text("[L7] 리소스팩 캡챠 발동! 화면의 리소스팩 프롬프트를 수락해주세요.", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                        rpCaptchaTrigger.accept(player);
                    } else {
                        player.sendMessage(net.kyori.adventure.text.Component.text("[L7] 리소스팩 캡챠 트리거가 초기화되지 않았습니다.", net.kyori.adventure.text.format.NamedTextColor.RED));
                    }
                }
                case MAP_CAPTCHA -> {
                    captchas.start(id, module);
                    player.sendMessage(net.kyori.adventure.text.Component.text("[L7] Map OCR 캡챠 발동!", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                    org.bukkit.Bukkit.getLogger().info("[L7-Debug] mapCaptchaTrigger is " + (mapCaptchaTrigger != null ? "READY" : "NULL"));
                    if (mapCaptchaTrigger != null) { mapCaptchaTrigger.accept(player); }
                }
                default -> {
                    if (penalty == com.l7defense.module.PenaltyAction.KICK) {
                        player.kick(net.kyori.adventure.text.Component.text("[L7 Defense] KICK 처벌 테스트!\n", net.kyori.adventure.text.format.NamedTextColor.RED)
                                .append(net.kyori.adventure.text.Component.text("모듈: " + module.getId() + "\n", net.kyori.adventure.text.format.NamedTextColor.WHITE))
                                .append(net.kyori.adventure.text.Component.text("다시 접속하실 수 있습니다.", net.kyori.adventure.text.format.NamedTextColor.GRAY)));
                    } else if (penalty == com.l7defense.module.PenaltyAction.BAN) {
                        player.kick(net.kyori.adventure.text.Component.text("[L7 Defense] BAN 처벌 테스트!\n", net.kyori.adventure.text.format.NamedTextColor.DARK_RED)
                                .append(net.kyori.adventure.text.Component.text("모듈: " + module.getId() + "\n", net.kyori.adventure.text.format.NamedTextColor.WHITE))
                                .append(net.kyori.adventure.text.Component.text("실제라면 영구 IP 차단 적용.\n", net.kyori.adventure.text.format.NamedTextColor.RED))
                                .append(net.kyori.adventure.text.Component.text("(테스트: IP 차단 없음. 다시 들어오세요)", net.kyori.adventure.text.format.NamedTextColor.GRAY)));
                    } else {
                        player.sendMessage(net.kyori.adventure.text.Component.text("[L7] NOTIFY 테스트 완료! 강퇴 없이 알림만 전송됨.", net.kyori.adventure.text.format.NamedTextColor.GREEN));
                    }
                }
            }
        });
    }

    public int getAttackThreshold() { return attackThreshold; }
    public int getDenialThreshold() { return denialThreshold; }
    public int getAttackWindowSeconds() { return attackWindowSeconds; }
    public long getBlockedCount() { return blockedIps.estimatedSize(); }
}
