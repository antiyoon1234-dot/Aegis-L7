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
    private AlertManager alertManager;
    private FirewallManager firewallManager;
    private com.l7defense.util.GeoIpManager geoIpManager;
    private com.l7defense.util.RedisManager redisManager;
    private com.l7defense.module.ModuleConfigManager moduleManager;

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

        // 기본 보안 설정
        int maxConn = config.getInt("rate-limit.max-connections", 5);
        int connWin = config.getInt("rate-limit.window-seconds", 30);
        int maxPing = config.getInt("ping-flood.max-pings", 15);
        int pingWin = config.getInt("ping-flood.window-seconds", 10);
        int blockDur = config.getInt("blocking.duration-minutes", 10);
        int maxCache = config.getInt("blocking.max-cache-size", 50000);
        int atkThresh = config.getInt("attack-detection.threshold", 10);
        int denThresh = config.getInt("attack-detection.denial-threshold", 50);
        int atkEvalSec = config.getInt("attack-detection.evaluation-interval-seconds", 30);
        int atkWin = config.getInt("attack-detection.window-seconds", 60);
        boolean botEnabled = config.getBoolean("bot-filter.enabled", true);
        int botThresh = config.getInt("bot-filter.suspicious-threshold", 3);
        int alertInt = config.getInt("alert.interval-seconds", 5);
        boolean nameBypass = config.getBoolean("whitelist.name-bypass-all", true);
        
        Set<String> wPlayers = java.util.Collections.unmodifiableSet(new HashSet<>(config.getStringList("whitelist.players")));
        Set<String> wIps = java.util.Collections.unmodifiableSet(IpUtils.normalizeAll(new HashSet<>(config.getStringList("whitelist.ips"))));

        // v1.3 / v1.4 고급 설정
        boolean pingJoin = config.getBoolean("advanced-heuristics.ping-before-join.enabled", true);
        boolean pingOnlyAtk = config.getBoolean("advanced-heuristics.ping-before-join.only-under-attack", true);
        boolean rejoinEn = config.getBoolean("advanced-heuristics.rejoin-verification.enabled", true);
        int rejoinMin = config.getInt("advanced-heuristics.rejoin-verification.min-delay-seconds", 3);
        int rejoinMax = config.getInt("advanced-heuristics.rejoin-verification.max-delay-seconds", 15);
        boolean hostEn = config.getBoolean("advanced-heuristics.hostname-validation.enabled", true);
        boolean hostNum = config.getBoolean("advanced-heuristics.hostname-validation.block-numeric-ip", true);
        List<String> hostDomains = config.getStringList("advanced-heuristics.hostname-validation.allowed-domains");
        
        // v1.4 GeoIP / Anti-VPN
        boolean vpnEn = config.getBoolean("anti-vpn.enabled", true);
        boolean vpnOnlyAtk = config.getBoolean("anti-vpn.only-under-attack", true);
        boolean blockForeign = config.getBoolean("geoip.block-foreign-under-attack", false);
        List<String> allowedCountries = config.getStringList("geoip.allowed-countries");
        
        // v1.4 Packet Spam
        boolean spamEn = config.getBoolean("post-login.packet-spam-filter.enabled", true);
        int spamLimit = config.getInt("post-login.packet-spam-filter.max-actions-per-sec", 50);

        // v1.5 Endgame
        boolean brandEn = config.getBoolean("endgame-features.brand-fingerprinting.enabled", true);
        boolean moveEn = config.getBoolean("endgame-features.movement-heuristics.enabled", true);
        boolean redisEn = config.getBoolean("endgame-features.redis-sync.enabled", false);
        String rHost = config.getString("endgame-features.redis-sync.host", "127.0.0.1");
        int rPort = config.getInt("endgame-features.redis-sync.port", 6379);
        String rPass = config.getString("endgame-features.redis-sync.password", "");

        // v1.6 모듈 매니저 및 명령어
        this.moduleManager = new com.l7defense.module.ModuleConfigManager(this); com.l7defense.module.ModuleConfigManager moduleManager = this.moduleManager;
        
        boolean fwEn = config.getBoolean("firewall.enabled", false);
        String fwBlock = config.getString("firewall.command-block", "");
        String fwUnblock = config.getString("firewall.command-unblock", "");

        // 매니저 초기화
        firewallManager = new FirewallManager(this, logger, fwEn, fwBlock, fwUnblock);
        com.l7defense.util.GeoIpManager geoIpManager = new com.l7defense.util.GeoIpManager(
                logger, vpnEn || blockForeign, vpnEn, blockForeign, allowedCountries
        );
        
        securityManager = new SecurityManager(
                maxConn, connWin, maxPing, pingWin, blockDur, maxCache,
                atkThresh, denThresh, atkWin, botThresh, rejoinMin, rejoinMax
        );
        securityManager.setModules(firewallManager, geoIpManager);
        securityManager.setModuleManager(moduleManager);
        
        // v1.5 Redis
        if (redisManager != null) {
            redisManager.shutdown();
        }
        redisManager = new com.l7defense.util.RedisManager(
                this, logger, securityManager, redisEn, rHost, rPort, rPass
        );
        securityManager.setRedisManager(redisManager);
        
        alertManager = new AlertManager(this, securityManager);

        logger.info("[모듈] GeoIP / VPN 방어: " + (vpnEn || blockForeign ? "활성화" : "비활성화"));
        logger.info("[모듈] Name Entropy Checker: 활성화");
        logger.info("[모듈] Post-Login Spam Filter: " + (spamEn ? "활성화" : "비활성화"));
        logger.info("[모듈] Endgame 3D Entity Captcha: 준비됨");
        logger.info("[모듈] Endgame Resource Pack Captcha: 준비됨");
        logger.info("[모듈] Zero-Day Map OCR Captcha: 준비됨");
        logger.info("[모듈] Zero-Day Exploit Crasher 방어: 활성화");
        logger.info("[모듈] Endgame Brand Fingerprinting: " + (brandEn ? "활성화" : "비활성화"));
        logger.info("[모듈] Endgame Movement AI Heuristics: " + (moveEn ? "활성화" : "비활성화"));

        // GUI 매니저 초기화
        com.l7defense.manager.AdminGuiManager guiManager = new com.l7defense.manager.AdminGuiManager(securityManager);
        getServer().getPluginManager().registerEvents(guiManager, this);

        // 명령어 등록 (중복 생성 방지 및 최신 객체 참조 유지)
        org.bukkit.command.PluginCommand cmd = getCommand("l7def");
        if (cmd != null) {
            com.l7defense.command.L7DefCommand handler = new com.l7defense.command.L7DefCommand(securityManager, alertManager, guiManager, this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        // 리스너 등록
        var pm = getServer().getPluginManager();
        pm.registerEvents(new ConnectionListener(securityManager, logger,
                botEnabled, nameBypass, wPlayers, wIps,
                pingJoin, pingOnlyAtk, rejoinEn, hostEn, hostNum, hostDomains,
                vpnEn, vpnOnlyAtk), this);
        pm.registerEvents(new PingFloodListener(securityManager, logger, wIps), this);
        
        // v1.4 & v1.5 & v1.6 & v1.7 캡챠 및 방어 리스너
        pm.registerEvents(new com.l7defense.listener.CaptchaListener(this, securityManager), this);
        pm.registerEvents(new com.l7defense.listener.PacketSpamListener(securityManager, spamEn, spamLimit), this);
        pm.registerEvents(new com.l7defense.listener.EntityCaptchaListener(this, securityManager, true), this);
        pm.registerEvents(new com.l7defense.listener.ResourcePackVerifier(this, securityManager, true), this);
        pm.registerEvents(new com.l7defense.listener.MovementHeuristicsListener(securityManager, moveEn), this);
        pm.registerEvents(new com.l7defense.listener.MapCaptchaListener(this, securityManager), this);
        pm.registerEvents(new com.l7defense.listener.ChatAiListener(securityManager), this);
        
        com.l7defense.listener.ExploitCrasherListener crasherListener = new com.l7defense.listener.ExploitCrasherListener(securityManager);
        pm.registerEvents(crasherListener, this);

        // v1.8 킬아우라 허니팟 및 매크로 주파수 분석기
        pm.registerEvents(new com.l7defense.listener.OffensiveDefenseListener(securityManager, this), this);

        // v1.9 월드 다운로더(맵 유출) 차단 및 조명 폭탄 방어
        com.l7defense.listener.ClientModDetectorListener wdlListener = new com.l7defense.listener.ClientModDetectorListener(securityManager);
        pm.registerEvents(new com.l7defense.listener.BlockCrasherListener(securityManager), this);

        // 패킷 크래셔 페이로드 채널
        try { getServer().getMessenger().registerIncomingPluginChannel(this, "minecraft:custom_payload", crasherListener); } catch (Exception ignored) {}
        
        // v1.5 플러그인 채널 (Brand Timing)
        com.l7defense.listener.BrandTimingListener brandListener = new com.l7defense.listener.BrandTimingListener(this, securityManager, brandEn);
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
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) { brandListener.recordQuit(e.getPlayer()); }
        }, this);

        // 스케줄러
        alertManager.startAlertScheduler(alertInt * 20L);
        getServer().getScheduler().runTaskTimerAsynchronously(this,
                () -> securityManager.evaluateAttackStatus(), atkEvalSec * 20L, atkEvalSec * 20L);
    }

    public void reloadPlugin() {
        getLogger().info("[리로드] 설정 재적용 시작...");
        if (alertManager != null) alertManager.stopAlertScheduler();
        getServer().getScheduler().cancelTasks(this);
        org.bukkit.event.HandlerList.unregisterAll(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        reloadConfig();
        initializeFromConfig();
        getLogger().info("[리로드] 설정 재적용 완료");
    }

    @Override
    public void onDisable() {
        if (alertManager != null) alertManager.stopAlertScheduler();
        getServer().getScheduler().cancelTasks(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        if (securityManager != null) securityManager.resetStats();
        if (redisManager != null) redisManager.shutdown();
        getLogger().info("비활성화 완료");
    }

    public SecurityManager getSecurityManager() { return securityManager; }
    public AlertManager getAlertManager() { return alertManager; }
}
