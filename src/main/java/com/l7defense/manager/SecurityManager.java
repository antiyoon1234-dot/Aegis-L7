package com.l7defense.manager;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.l7defense.util.FirewallManager;
import com.l7defense.util.GeoIpManager;
import com.l7defense.util.IpUtils;
import com.l7defense.util.RedisManager;

import java.util.concurrent.TimeUnit;
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

    // ── Caffeine Cache 인스턴스 ─────────────────────────────
    private final Cache<String, AtomicInteger> connectionAttempts;
    private final Cache<String, Boolean> blockedIps;
    private final Cache<String, AtomicInteger> pingAttempts;
    private final Cache<Long, String> recentBlockEvents;
    private final Cache<Long, Boolean> recentDeniedEvents;
    private final Cache<Long, Boolean> recentAttemptEvents;
    private final Cache<String, AtomicInteger> suspiciousNameCounter;

    // ── v1.3 / v1.4 신규 캐시 ──────────────────────────────────────
    private final Cache<String, Boolean> recentPings;
    private final Cache<String, Long> pendingVerification;
    private final Cache<String, Boolean> verifiedIps;
    
    /** 인게임 GUI 캡챠를 통과해야 하는 IP 목록 (v1.4) */
    private final Cache<String, Boolean> pendingGuiCaptcha;

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

    public void setGuiCaptchaTrigger(java.util.function.Consumer<org.bukkit.entity.Player> trigger) { this.guiCaptchaTrigger = trigger; }
    public void setEntityCaptchaTrigger(java.util.function.Consumer<org.bukkit.entity.Player> trigger) { this.entityCaptchaTrigger = trigger; }
    public void setMapCaptchaTrigger(java.util.function.Consumer<org.bukkit.entity.Player> trigger) { this.mapCaptchaTrigger = trigger; }

    public SecurityManager(int maxConnections, int connectionWindowSec,
                           int maxPings, int pingWindowSec,
                           int blockDurationMin, int maxCacheSize,
                           int attackThreshold, int denialThreshold,
                           int attackWindowSec, int suspiciousNameThreshold,
                           int rejoinMinDelay, int rejoinMaxDelay) {
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

        this.recentPings = Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(50_000).build();
        this.pendingVerification = Caffeine.newBuilder().expireAfterWrite(rejoinMaxDelay + 5, TimeUnit.SECONDS).maximumSize(10_000).build();
        this.verifiedIps = Caffeine.newBuilder().expireAfterWrite(12, TimeUnit.HOURS).maximumSize(50_000).build();
        
        // v1.4
        this.pendingGuiCaptcha = Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(10_000).build();
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
        if (moduleManager == null) return;
        if (!moduleManager.isEnabled(module)) return;
        
        com.l7defense.module.PenaltyAction action = moduleManager.getPenalty(module);
        String ip = IpUtils.getIp(player);
        
        if (action == com.l7defense.module.PenaltyAction.BAN) {
            blockIp(ip, reason);
        }

        // 비동기 이벤트(AsyncPlayerChatEvent 등)에서 호출될 수 있으므로,
        // 순수 Bukkit API (Kick, SendMessage)는 반드시 메인 스레드(Sync)에서 실행
        org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("L7DefensePlugin");
        if (plugin != null) {
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;

                if (action == com.l7defense.module.PenaltyAction.BAN) {
                    player.kick(net.kyori.adventure.text.Component.text("서버 보안 정책에 의해 차단되었습니다.\n\n§c사유: " + reason, net.kyori.adventure.text.format.NamedTextColor.RED));
                } else if (action == com.l7defense.module.PenaltyAction.KICK) {
                    player.kick(net.kyori.adventure.text.Component.text("비정상적인 접근/행동으로 강제 퇴장되었습니다.\n\n§c사유: " + reason, net.kyori.adventure.text.format.NamedTextColor.RED));
                } else if (action == com.l7defense.module.PenaltyAction.NOTIFY) {
                    org.bukkit.Bukkit.getLogger().warning("[L7-Notify] " + player.getName() + "(" + ip + ") - " + reason);
                    for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                        if (p.isOp()) p.sendMessage(net.kyori.adventure.text.Component.text("[L7 경고] " + player.getName() + ": " + reason, net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                    }
                }
            });
        }
    }

    /**
     * Pre-Login 환경용 패널티 핸들러 (v1.6)
     * Player 객체가 생성되기 전이므로 AsyncPlayerPreLoginEvent를 통해 제어합니다.
     */
    public void handlePreLoginViolation(org.bukkit.event.player.AsyncPlayerPreLoginEvent event, com.l7defense.module.DefenseModule module, String reason) {
        if (moduleManager == null || !moduleManager.isEnabled(module)) return;

        com.l7defense.module.PenaltyAction action = moduleManager.getPenalty(module);
        String ip = event.getAddress().getHostAddress();
        String name = event.getName();

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
        if (counter.incrementAndGet() >= suspiciousNameThreshold) {
            blockIpNormalized(ip, "반복 봇 닉네임");
            return true;
        }
        return false;
    }

    public void blockIp(String rawIp, String reason) {
        blockIpNormalized(IpUtils.normalize(rawIp), reason);
    }

    private void blockIpNormalized(String normalizedIp, String reason) {
        Boolean previous = blockedIps.asMap().putIfAbsent(normalizedIp, Boolean.TRUE);
        if (previous == null) {
            lifetimeBlockedCount.increment();
            recentBlockEvents.put(blockEventId.incrementAndGet(), normalizedIp);
            
            // 방화벽(iptables) 연동 차단
            if (firewallManager != null) {
                firewallManager.executeBlock(normalizedIp);
            }
        }
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
        
        if (wasBlocked && firewallManager != null) {
            firewallManager.executeUnblock(ip);
        }
        return wasBlocked;
    }

    // ── 3. 재접속 검증 (Soft Captcha) ───────────────────────

    public boolean isVerified(String rawIp) {
        return verifiedIps.getIfPresent(IpUtils.normalize(rawIp)) != null;
    }

    public void startVerification(String rawIp) {
        pendingVerification.put(IpUtils.normalize(rawIp), System.currentTimeMillis());
    }

    public VerificationResult checkVerification(String rawIp) {
        String ip = IpUtils.normalize(rawIp);
        Long startTime = pendingVerification.getIfPresent(ip);
        if (startTime == null) return VerificationResult.NEW;

        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed < rejoinMinDelay * 1000L) {
            return VerificationResult.WAITING; // 너무 빠름 (봇 스크립트 특징)
        } else if (elapsed > rejoinMaxDelay * 1000L) {
            pendingVerification.invalidate(ip);
            return VerificationResult.EXPIRED; // 늦음
        } else {
            // 검증 성공
            pendingVerification.invalidate(ip);
            verifiedIps.put(ip, Boolean.TRUE);
            return VerificationResult.PASSED;
        }
    }

    // ── 4. VPN 및 GeoIP 검사 (v1.4 통합) ───────────────────
    
    public boolean isRestrictedGeoIp(String rawIp) {
        if (geoIpManager == null) return false;
        return geoIpManager.isRestricted(IpUtils.normalize(rawIp));
    }

    // ── 4-2. 캡챠 상태 플래그 (v1.4 / v1.5) ────────────────────────────────

    public void flagForGuiCaptcha(String rawIp) {
        pendingGuiCaptcha.put(IpUtils.normalize(rawIp), Boolean.TRUE);
    }
    public boolean needsGuiCaptcha(String rawIp) {
        return pendingGuiCaptcha.getIfPresent(IpUtils.normalize(rawIp)) != null;
    }
    public void passGuiCaptcha(String rawIp) {
        String ip = IpUtils.normalize(rawIp);
        pendingGuiCaptcha.invalidate(ip);
        verifiedIps.put(ip, Boolean.TRUE); 
    }

    // (v1.5) Resource Pack Captcha 플래그
    private final Cache<String, Boolean> pendingRpCaptcha = Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();
    public void flagForResourcePackCaptcha(String rawIp) { pendingRpCaptcha.put(IpUtils.normalize(rawIp), Boolean.TRUE); }
    public boolean needsResourcePackCaptcha(String rawIp) { return pendingRpCaptcha.getIfPresent(IpUtils.normalize(rawIp)) != null; }
    public void passResourcePackCaptcha(String rawIp) { 
        String ip = IpUtils.normalize(rawIp);
        pendingRpCaptcha.invalidate(ip);
        verifiedIps.put(ip, Boolean.TRUE); 
    }

    // (v1.5) 3D Entity Captcha 플래그
    private final Cache<String, Boolean> pendingEntityCaptcha = Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();
    public void flagForEntityCaptcha(String rawIp) { pendingEntityCaptcha.put(IpUtils.normalize(rawIp), Boolean.TRUE); }
    public boolean needsEntityCaptcha(String rawIp) { return pendingEntityCaptcha.getIfPresent(IpUtils.normalize(rawIp)) != null; }
    public void passEntityCaptcha(String rawIp) { 
        String ip = IpUtils.normalize(rawIp);
        pendingEntityCaptcha.invalidate(ip);
        verifiedIps.put(ip, Boolean.TRUE); 
    }

    // (v1.6) Map OCR Captcha 플래그
    private final Cache<String, Boolean> pendingMapCaptcha = Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();
    public void flagForMapCaptcha(String rawIp) { pendingMapCaptcha.put(IpUtils.normalize(rawIp), Boolean.TRUE); }
    public boolean needsMapCaptcha(String rawIp) { return pendingMapCaptcha.getIfPresent(IpUtils.normalize(rawIp)) != null; }
    public void passMapCaptcha(String rawIp) { 
        String ip = IpUtils.normalize(rawIp);
        pendingMapCaptcha.invalidate(ip);
        verifiedIps.put(ip, Boolean.TRUE); 
    }

    // ── 5. 이벤트 및 공격 상태 평가 ─────────────────────────

    private void recordAttempt() {
        recentAttemptEvents.put(attemptEventId.incrementAndGet(), Boolean.TRUE);
    }

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
        blockedIps.invalidateAll();
        pingAttempts.invalidateAll();
        recentBlockEvents.invalidateAll();
        recentDeniedEvents.invalidateAll();
        recentAttemptEvents.invalidateAll();
        suspiciousNameCounter.invalidateAll();
        recentPings.invalidateAll();
        pendingVerification.invalidateAll();
        verifiedIps.invalidateAll();
        pendingGuiCaptcha.invalidateAll();
        pendingRpCaptcha.invalidateAll();
        pendingEntityCaptcha.invalidateAll();
        pendingMapCaptcha.invalidateAll();
        underAttack = false;
    }

    public int getMaxConnectionsPerWindow() { return maxConnectionsPerWindow; }
    public int getConnectionWindowSeconds() { return connectionWindowSeconds; }
    public int getMaxPingsPerWindow() { return maxPingsPerWindow; }
    public int getPingWindowSeconds() { return pingWindowSeconds; }
        public void scheduleCaptchaTimeout(org.bukkit.entity.Player player, String ip, com.l7defense.module.DefenseModule module) {
        org.bukkit.plugin.Plugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player != null && player.isOnline()) {
                boolean stillPending = false;
                switch (module) {
                    case GUI_CAPTCHA -> stillPending = needsGuiCaptcha(ip);
                    case ENTITY_CAPTCHA -> stillPending = needsEntityCaptcha(ip);
                    case MAP_CAPTCHA -> stillPending = needsMapCaptcha(ip);
                    case RP_CAPTCHA -> stillPending = needsResourcePackCaptcha(ip);
                }
                
                if (stillPending) {
                    // 강제 밴 처리
                    String banReason = "캡챠 30초 타임아웃 미인증";
                    org.bukkit.Bukkit.getLogger().warning("[L7 Defense] " + player.getName() + " 캡챠 30초 미인증 밴 처리됨.");
                    getModuleManager().setPenalty(module, com.l7defense.module.PenaltyAction.BAN);
                    handleViolation(player, module, banReason);
                }
            }
        }, 30L * 20L); // 30초 후 확인
    }

    public int getBlockDurationMinutes() { return blockDurationMinutes; }

    public void simulateTest(org.bukkit.entity.Player player, com.l7defense.module.DefenseModule module) {
        String ip = com.l7defense.util.IpUtils.getIp(player);
        com.l7defense.module.PenaltyAction penalty = getModuleManager().getPenalty(module);

                // 1. OP 알림 전송
        org.bukkit.Bukkit.getLogger().warning("[L7-Notify] " + player.getName() + " - " + module.getId() + " 테스트");
        for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (p.isOp()) {
                p.sendMessage(net.kyori.adventure.text.Component.text(
                    "[L7 테스트] " + player.getName() + " → " + module.getDescription() + " 발동!",
                    net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
            }
        }

        // 강제로 플러그인 객체 찾기
        org.bukkit.plugin.Plugin plugin = org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(getClass());
        org.bukkit.Bukkit.getLogger().info("[L7-Debug] Plugin Found: " + (plugin != null ? plugin.getName() : "NULL"));

        if (plugin == null) return;

        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            org.bukkit.Bukkit.getLogger().info("[L7-Debug] Scheduler Started for: " + module.getId());
            switch (module) {
                case GUI_CAPTCHA -> {
                    flagForGuiCaptcha(ip);
                    player.sendMessage(net.kyori.adventure.text.Component.text("§e[L7] GUI 캡챠 발동!", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                    org.bukkit.Bukkit.getLogger().info("[L7-Debug] guiCaptchaTrigger is " + (guiCaptchaTrigger != null ? "READY" : "NULL"));
                    if (guiCaptchaTrigger != null) { guiCaptchaTrigger.accept(player); scheduleCaptchaTimeout(player, ip, module); }
                }
                case ENTITY_CAPTCHA -> {
                    flagForEntityCaptcha(ip);
                    player.sendMessage(net.kyori.adventure.text.Component.text("§e[L7] 3D Entity 캡챠 발동!", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                    org.bukkit.Bukkit.getLogger().info("[L7-Debug] entityCaptchaTrigger is " + (entityCaptchaTrigger != null ? "READY" : "NULL"));
                    if (entityCaptchaTrigger != null) { entityCaptchaTrigger.accept(player); scheduleCaptchaTimeout(player, ip, module); }
                }
                case RP_CAPTCHA -> {
                    flagForResourcePackCaptcha(ip);
                    player.sendMessage(net.kyori.adventure.text.Component.text("§e[L7] 리소스팩 캡챠 발동! (리소스팩 적용은 클라이언트 접속 시에만 가능하여 재접속이 필요합니다.)", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                }
                case MAP_CAPTCHA -> {
                    flagForMapCaptcha(ip);
                    player.sendMessage(net.kyori.adventure.text.Component.text("§e[L7] Map OCR 캡챠 발동!", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
                    org.bukkit.Bukkit.getLogger().info("[L7-Debug] mapCaptchaTrigger is " + (mapCaptchaTrigger != null ? "READY" : "NULL"));
                    if (mapCaptchaTrigger != null) { mapCaptchaTrigger.accept(player); scheduleCaptchaTimeout(player, ip, module); }
                }
                default -> {
                    if (penalty == com.l7defense.module.PenaltyAction.KICK) {
                        player.kick(net.kyori.adventure.text.Component.text("§c[L7 Defense] KICK 처벌 테스트!\n§f모듈: " + module.getId() + "\n§7다시 접속하실 수 있습니다."));
                    } else if (penalty == com.l7defense.module.PenaltyAction.BAN) {
                        player.kick(net.kyori.adventure.text.Component.text("§4[L7 Defense] BAN 처벌 테스트!\n§f모듈: " + module.getId() + "\n§c실제라면 영구 IP 차단 적용.\n§7(테스트: IP 차단 없음. 다시 들어오세요)"));
                    } else {
                        player.sendMessage(net.kyori.adventure.text.Component.text("§a[L7] NOTIFY 테스트 완료! 강퇴 없이 알림만 전송됨.", net.kyori.adventure.text.format.NamedTextColor.GREEN));
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
