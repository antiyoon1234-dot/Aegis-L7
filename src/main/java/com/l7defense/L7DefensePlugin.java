package com.l7defense;

import com.l7defense.command.L7DefCommand;
import com.l7defense.listener.ConnectionListener;
import com.l7defense.listener.PingFloodListener;
import com.l7defense.manager.AlertManager;
import com.l7defense.manager.SecurityManager;
import com.l7defense.util.FirewallManager;
import com.l7defense.util.IpUtils;
import com.l7defense.util.VpnChecker;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * L7 Defense Plugin — 고급 휴리스틱 L7 DDoS 방어 시스템
 *
 * <h3>v1.3 변경사항:</h3>
 * <ul>
 *   <li>고급 휴리스틱 기능 통합 (Ping-before-join, Soft Captcha, Hostname 검증)</li>
 *   <li>VPN/데이터센터 IP 검증 모듈 추가</li>
 *   <li>Linux 방화벽(iptables/ufw) 연동 기능 추가</li>
 * </ul>
 *
 * @version 1.3.0
 */
public final class L7DefensePlugin extends JavaPlugin {

    private SecurityManager securityManager;
    private final java.util.List<AutoCloseable> lifecycles = new java.util.ArrayList<>();
    private AlertManager alertManager;
    private FirewallManager firewallManager;
    private com.l7defense.util.GeoIpManager geoIpManager;
    private com.l7defense.util.RedisManager redisManager;
    private com.l7defense.module.ModuleConfigManager moduleManager;
    private com.l7defense.manager.PacketAntiXrayManager packetAntiXrayManager;

    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();
        Logger logger = getLogger();

        logger.info("═══════════════════════════════════════════");
        logger.info("  L7 Defense Plugin v" + getDescription().getVersion());
        logger.info("  v1.3 - 휴리스틱 기반 방어 모드 초기화 중...");
        logger.info("═══════════════════════════════════════════");

        saveDefaultConfig();
        initializeFromConfig();

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("═══════════════════════════════════════════");
        logger.info("  L7 Defense Plugin 활성화 완료! (" + elapsed + "ms)");
        logger.info("═══════════════════════════════════════════");
    }

    private void initializeFromConfig() {
        Logger logger = getLogger();
        FileConfiguration config = getConfig();
        validateConfiguration(config);

        // 기본 보안 설정
        int maxConn = config.getInt("rate-limit.max-connections", 20);
        int connWin = config.getInt("rate-limit.window-seconds", 30);
        int maxPing = config.getInt("ping-flood.max-pings", 15);
        int pingWin = config.getInt("ping-flood.window-seconds", 10);
        int blockDur = config.getInt("blocking.duration-minutes", 10);
        int maxCache = config.getInt("blocking.max-cache-size", 50000);
        int atkThresh = config.getInt("attack-detection.threshold", 10);
        int denThresh = config.getInt("attack-detection.denial-threshold", 50);
        int atkEvalSec = config.getInt("attack-detection.evaluation-interval-seconds", 30);
        int atkWin = config.getInt("attack-detection.window-seconds", 60);
        int botThresh = config.getInt("bot-filter.suspicious-threshold", 3);
        int alertInt = config.getInt("alert.interval-seconds", 5);
        boolean nameBypass = config.getBoolean("whitelist.name-bypass-all", true);

        Set<String> wPlayers = java.util.Collections.unmodifiableSet(new HashSet<>(config.getStringList("whitelist.players")));
        Set<String> wIps = java.util.Collections.unmodifiableSet(IpUtils.normalizeAll(new HashSet<>(config.getStringList("whitelist.ips"))));

        // v1.3 / v1.4 고급 설정 (누락된 키의 안전한 기본값은 false로 설정하여 오탐 킥 방지)
        boolean pingOnlyAtk = config.getBoolean("advanced-heuristics.ping-before-join.only-under-attack", true);
        boolean rejoinEn = config.getBoolean("advanced-heuristics.rejoin-verification.enabled", false);
        int rejoinMin = config.getInt("advanced-heuristics.rejoin-verification.min-delay-seconds", 3);
        int rejoinMax = config.getInt("advanced-heuristics.rejoin-verification.max-delay-seconds", 15);
        boolean hostEn = config.getBoolean("advanced-heuristics.hostname-validation.enabled", false);
        boolean hostNum = config.getBoolean("advanced-heuristics.hostname-validation.block-numeric-ip", false);
        List<String> hostDomains = config.getStringList("advanced-heuristics.hostname-validation.allowed-domains");

        // v1.4 GeoIP / Anti-VPN
        boolean vpnOnlyAtk = config.getBoolean("anti-vpn.only-under-attack", true);
        boolean blockForeign = config.getBoolean("geoip.block-foreign-under-attack", false);
        List<String> allowedCountries = config.getStringList("geoip.allowed-countries");

        // v1.4 Packet Spam
        int spamLimit = config.getInt("post-login.packet-spam-filter.max-actions-per-sec", 40);

        // v1.5 Endgame
        boolean redisEn = config.getBoolean("endgame-features.redis-sync.enabled", false);
        String rHost = config.getString("endgame-features.redis-sync.host", "127.0.0.1");
        int rPort = config.getInt("endgame-features.redis-sync.port", 6379);
        String rPass = config.getString("endgame-features.redis-sync.password", "");

        // v1.6 모듈 매니저 및 명령어 (인게임 GUI / toggle 명령어로 실시간 ON/OFF 제어)
        this.moduleManager = new com.l7defense.module.ModuleConfigManager(this); com.l7defense.module.ModuleConfigManager moduleManager = this.moduleManager;

        boolean botEnabled = moduleManager.isEnabled(com.l7defense.module.DefenseModule.ENTROPY);
        boolean pingJoin = moduleManager.isEnabled(com.l7defense.module.DefenseModule.PING);
        boolean vpnEn = moduleManager.isEnabled(com.l7defense.module.DefenseModule.VPN);
        boolean spamEn = moduleManager.isEnabled(com.l7defense.module.DefenseModule.PACKET_SPAM);
        boolean brandEn = moduleManager.isEnabled(com.l7defense.module.DefenseModule.BRAND);
        boolean moveEn = moduleManager.isEnabled(com.l7defense.module.DefenseModule.MOVEMENT);

        boolean fwEn = config.getBoolean("firewall.enabled", false);
        String fwBlock = config.getString("firewall.command-block", "");
        String fwUnblock = config.getString("firewall.command-unblock", "");

        // 매니저 초기화 (GeoIpManager는 런타임 GUI 토글로 VPN 모듈을 켤 때도 동작하도록 준비)
        if (firewallManager == null) firewallManager = new FirewallManager(this, logger, fwEn, fwBlock, fwUnblock);
        else firewallManager.updateConfiguration(fwEn, fwBlock, fwUnblock);
        geoIpManager = new com.l7defense.util.GeoIpManager(
                logger, true, true, blockForeign, allowedCountries
        );

        securityManager = new SecurityManager(
                maxConn, connWin, maxPing, pingWin, blockDur, maxCache,
                atkThresh, denThresh, atkWin, botThresh, rejoinMin, rejoinMax
        );
        securityManager.setModules(firewallManager, geoIpManager);
        securityManager.setModuleManager(moduleManager);
        var sessions = new com.l7defense.manager.PlayerSessions(this);
        securityManager.setSessions(sessions);
        lifecycles.add(sessions);
        getServer().getPluginManager().registerEvents(sessions, this);

        // v1.5 Redis
        if (redisManager != null) {
            redisManager.shutdown();
        }
        redisManager = new com.l7defense.util.RedisManager(
                this, logger, securityManager, redisEn, rHost, rPort, rPass
        );
        securityManager.setRedisManager(redisManager);

        alertManager = new AlertManager(this, securityManager);
        securityManager.setAlertManager(alertManager);

        logger.info("[모듈] GeoIP / VPN 방어: " + (vpnEn || blockForeign ? "활성화" : "비활성화"));
        logger.info("[모듈] Name Entropy Checker: 활성화");
        logger.info("[모듈] Post-Login Spam Filter: " + (spamEn ? "활성화" : "비활성화"));
        logger.info("[모듈] Endgame 3D Entity Captcha: 준비됨");
        logger.info("[모듈] Endgame Resource Pack Captcha: 준비됨");
        logger.info("[모듈] Zero-Day Map OCR Captcha: 준비됨");
        logger.info("[모듈] Zero-Day Exploit Crasher 방어: 활성화");
        logger.info("[모듈] Endgame Brand Fingerprinting: " + (brandEn ? "활성화" : "비활성화"));
        logger.info("[모듈] Endgame Movement AI Heuristics: " + (moveEn ? "활성화" : "비활성화"));

        // GUI 매니저 초기화 (리로드/종료 시 열려있는 GUI 자동 닫기 등록)
        com.l7defense.manager.AdminGuiManager guiManager = new com.l7defense.manager.AdminGuiManager(securityManager);
        lifecycles.add(guiManager);
        getServer().getPluginManager().registerEvents(guiManager, this);

        // 명령어 등록 (중복 생성 방지 및 최신 객체 참조 유지)
        org.bukkit.command.PluginCommand cmd = getCommand("l7def");
        if (cmd != null) {
            com.l7defense.command.L7DefCommand handler = new com.l7defense.command.L7DefCommand(securityManager, alertManager, guiManager, this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        // 리스너 등록 (bot/ping/vpn 활성 여부는 ModuleConfigManager가 런타임으로 실시간 제어)
        var pm = getServer().getPluginManager();
        pm.registerEvents(new ConnectionListener(securityManager, logger,
                true, nameBypass, wPlayers, wIps,
                true, pingOnlyAtk, rejoinEn, hostEn, hostNum, hostDomains,
                true, vpnOnlyAtk), this);
        pm.registerEvents(new PingFloodListener(securityManager, logger, wIps), this);

        // v1.4 & v1.5 & v1.6 & v1.7 캡챠 및 방어 리스너
        com.l7defense.listener.CaptchaListener guiCaptcha = new com.l7defense.listener.CaptchaListener(this, securityManager);
        com.l7defense.listener.EntityCaptchaListener entityCaptcha = new com.l7defense.listener.EntityCaptchaListener(this, securityManager, true);
        com.l7defense.listener.MapCaptchaListener mapCaptcha = new com.l7defense.listener.MapCaptchaListener(this, securityManager);

        lifecycles.add(guiCaptcha);
        lifecycles.add(entityCaptcha);
        lifecycles.add(mapCaptcha);
        pm.registerEvents(guiCaptcha, this);
        com.l7defense.listener.PacketSpamListener packetSpamListener = new com.l7defense.listener.PacketSpamListener(securityManager, spamEn, spamLimit);
        lifecycles.add(packetSpamListener);
        pm.registerEvents(packetSpamListener, this);
        pm.registerEvents(entityCaptcha, this);
        var rpCaptcha = new com.l7defense.listener.ResourcePackVerifier(this, securityManager, true);
        lifecycles.add(rpCaptcha);
        pm.registerEvents(rpCaptcha, this);
        var movementListener = new com.l7defense.listener.MovementHeuristicsListener(securityManager, moveEn);
        lifecycles.add(movementListener);
        pm.registerEvents(movementListener, this);
        pm.registerEvents(mapCaptcha, this);
        var chatAi = new com.l7defense.listener.ChatAiListener(securityManager);
        lifecycles.add(chatAi);
        pm.registerEvents(chatAi, this);

        securityManager.setGuiCaptchaTrigger(guiCaptcha::openCaptcha);
        securityManager.setEntityCaptchaTrigger(entityCaptcha::spawnCaptchaEntity);
        securityManager.setMapCaptchaTrigger(mapCaptcha::triggerMapCaptcha);
        securityManager.setRpCaptchaTrigger(rpCaptcha::startVerification);

        com.l7defense.listener.ExploitCrasherListener crasherListener = new com.l7defense.listener.ExploitCrasherListener(securityManager);
        lifecycles.add(crasherListener);
        pm.registerEvents(crasherListener, this);

        // v1.8 킬아우라 허니팟 및 매크로 주파수 분석기
        var offensive = new com.l7defense.listener.OffensiveDefenseListener(securityManager, this);
        lifecycles.add(offensive);
        pm.registerEvents(offensive, this);

        // v1.9 월드 다운로더(맵 유출) 차단 및 조명 폭탄 방어
        com.l7defense.listener.ClientModDetectorListener wdlListener = new com.l7defense.listener.ClientModDetectorListener(securityManager);
        com.l7defense.listener.BlockCrasherListener blockCrasherListener = new com.l7defense.listener.BlockCrasherListener(securityManager);
        lifecycles.add(blockCrasherListener);
        pm.registerEvents(blockCrasherListener, this);

        // v2.0 통계 및 패킷 단위 광석 난수화 엑스레이 방어
        boolean xrayPacketEn = config.getBoolean("anti_xray.packet-obfuscation.enabled", true);
        boolean xrayPaperAuto = config.getBoolean("anti_xray.packet-obfuscation.auto-configure-paper-engine2", true);
        boolean xrayMasking = config.getBoolean("anti_xray.packet-obfuscation.client-packet-masking", true);
        boolean xrayDecoys = config.getBoolean("anti_xray.packet-obfuscation.packet-decoy-traps", true);
        int maxDecoys = config.getInt("anti_xray.packet-obfuscation.max-decoys-per-player", 3);
        int decoyMinDist = config.getInt("anti_xray.packet-obfuscation.decoy-min-distance", 4);
        int decoyMaxDist = config.getInt("anti_xray.packet-obfuscation.decoy-max-distance", 8);
        int decoyLifetime = config.getInt("anti_xray.packet-obfuscation.decoy-lifetime-seconds", 45);

        int minTotal = config.getInt("anti_xray.heuristics.min-total-blocks", 12);
        int minRare = config.getInt("anti_xray.heuristics.min-rare-ores", 3);
        double ratio = config.getDouble("anti_xray.heuristics.rare-ore-ratio", 0.20);

        com.l7defense.manager.PacketAntiXrayManager packetAntiXray = new com.l7defense.manager.PacketAntiXrayManager(
                this, securityManager, xrayPacketEn, xrayPaperAuto, xrayMasking, xrayDecoys,
                maxDecoys, decoyMinDist, decoyMaxDist, decoyLifetime
        );
        this.packetAntiXrayManager = packetAntiXray;
        lifecycles.add(packetAntiXray);

        com.l7defense.listener.AntiXrayListener antiXrayListener = new com.l7defense.listener.AntiXrayListener(
                securityManager, minTotal, minRare, ratio
        );
        antiXrayListener.setPacketAntiXrayManager(packetAntiXray);
        lifecycles.add(antiXrayListener);
        pm.registerEvents(antiXrayListener, this);

        // v1.5 플러그인 채널 (Brand Timing)
        com.l7defense.listener.BrandTimingListener brandListener = new com.l7defense.listener.BrandTimingListener(this, securityManager, brandEn);
        lifecycles.add(brandListener);
        try { getServer().getMessenger().registerIncomingPluginChannel(this, "minecraft:brand", brandListener); } catch (Exception ignored) {}

        // 1.13+ 네임스페이스 형식으로 채널 등록 (legacy WDL|INIT 대신 wdl:init 등 사용)
        try {
            getServer().getMessenger().registerIncomingPluginChannel(this, "wdl:init", wdlListener);
            getServer().getMessenger().registerIncomingPluginChannel(this, "wdl:control", wdlListener);
            getServer().getMessenger().registerIncomingPluginChannel(this, "worlddownloader:init", wdlListener);
        } catch (Exception ignored) {}

        pm.registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) { brandListener.recordJoin(e.getPlayer()); }
            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
                brandListener.recordQuit(e.getPlayer());
                alertManager.removeMutedPlayer(e.getPlayer().getUniqueId());
            }
        }, this);

        // 스케줄러
        alertManager.startAlertScheduler(Math.max(1, alertInt) * 20L);
        SecurityManager currentSecurity = securityManager;
        getServer().getScheduler().runTaskTimerAsynchronously(this,
                currentSecurity::evaluateAttackStatus, Math.max(1, atkEvalSec) * 20L, Math.max(1, atkEvalSec) * 20L);
    }

    public boolean reloadPlugin() {
        getLogger().info("[리로드] 설정 재적용 시작...");
        reloadConfig();
        try { validateConfiguration(getConfig()); }
        catch (IllegalArgumentException ex) {
            getLogger().warning("[리로드] 기존 방어 설정 유지: " + ex.getMessage());
            return false;
        }
        stopRuntime();
        getServer().getScheduler().cancelTasks(this);
        org.bukkit.event.HandlerList.unregisterAll(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        initializeFromConfig();
        getLogger().info("[리로드] 설정 재적용 완료");
        return true;
    }

    private static void validateConfiguration(FileConfiguration config) {
        for (String path : new String[] {"rate-limit.max-connections", "rate-limit.window-seconds", "ping-flood.max-pings",
                "ping-flood.window-seconds", "blocking.duration-minutes", "blocking.max-cache-size", "attack-detection.threshold",
                "attack-detection.denial-threshold", "attack-detection.window-seconds", "bot-filter.suspicious-threshold",
                "post-login.packet-spam-filter.max-actions-per-sec",
                "anti_xray.heuristics.min-total-blocks", "anti_xray.heuristics.min-rare-ores",
                "anti_xray.packet-obfuscation.decoy-lifetime-seconds"}) {
            if (config.contains(path) && config.getInt(path) < 1) throw new IllegalArgumentException(path + " must be positive");
        }
        if (config.contains("anti_xray.heuristics.rare-ore-ratio")) {
            double ratio = config.getDouble("anti_xray.heuristics.rare-ore-ratio");
            if (ratio <= 0.0 || ratio > 1.0) throw new IllegalArgumentException("anti_xray.heuristics.rare-ore-ratio must be in (0.0, 1.0]");
        }
        int min = config.getInt("advanced-heuristics.rejoin-verification.min-delay-seconds", 3);
        int max = config.getInt("advanced-heuristics.rejoin-verification.max-delay-seconds", 15);
        if (min < 0 || max < Math.max(1, min)) throw new IllegalArgumentException("Invalid rejoin verification delay range");
    }

    @Override
    public void onDisable() {
        stopRuntime();
        getServer().getScheduler().cancelTasks(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        if (firewallManager != null) firewallManager.close();
        getLogger().info("비활성화 완료");
    }

    private void stopRuntime() {
        if (alertManager != null) alertManager.close();
        if (securityManager != null) securityManager.shutdown();
        if (redisManager != null) redisManager.shutdown();
        if (geoIpManager != null) geoIpManager.close();
        for (int i = lifecycles.size() - 1; i >= 0; i--) {
            try { lifecycles.get(i).close(); }
            catch (Exception ex) { getLogger().log(java.util.logging.Level.WARNING, "[L7] 리소스 정리 실패", ex); }
        }
        lifecycles.clear();
    }

    public SecurityManager getSecurityManager() { return securityManager; }
    public AlertManager getAlertManager() { return alertManager; }
    public com.l7defense.manager.PacketAntiXrayManager getPacketAntiXrayManager() { return packetAntiXrayManager; }
}
