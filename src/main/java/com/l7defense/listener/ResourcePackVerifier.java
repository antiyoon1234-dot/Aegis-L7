package com.l7defense.listener;

import com.l7defense.manager.SecurityManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 리소스팩 검증(Resource Pack Verification)을 통한 엔드게임 봇 차단.
 * <p>
 * 접속 시 1KB 크기의 더미 리소스팩을 강제로 전송합니다.
 * 봇 스크립트들은 이를 다운로드하고 적용하는 로직이 없어 응답하지 못하거나 DECLINED를 보냅니다.
 * 성공적으로 로드(SUCCESSFULLY_LOADED)한 진짜 클라이언트만 캡챠를 통과합니다.
 */
public final class ResourcePackVerifier implements Listener, AutoCloseable {

    private final Plugin plugin;
    private final SecurityManager securityManager;
    private final boolean enabled;

    // 타임아웃 킥 관리를 위한 작업 맵
    private final java.util.Map<UUID, UUID> pendingVerifications = new ConcurrentHashMap<>();
    private final com.l7defense.util.PlayerTasks tasks;

    private static final String DUMMY_URL = "https://raw.githubusercontent.com/antiyoon1234-dot/Aegis-L7/main/src/main/resources/captcha/verification-v1.zip";

    // 빈 해시를 사용할 경우 1.20+ 에서는 종종 오류가 나므로 아예 null 혹은 빈 해시 바이트 배열 사용.
    // 혹은 byte array 20자리 빈 공간
    // 0으로 채워진 해시 (Spigot 스펙): 길이만 맞고 실제 SHA-1이 아니므로 아래 검증 팩의 해시로 대체합니다.
    private static final byte[] DUMMY_HASH = java.util.HexFormat.of().parseHex("e47b740c54f7192c31c63f4cb7d30b9818d2adc1");

    public ResourcePackVerifier(Plugin plugin, SecurityManager securityManager, boolean enabled) {
        this.plugin = plugin;
        this.tasks = new com.l7defense.util.PlayerTasks(plugin);
        this.securityManager = securityManager;
        this.enabled = enabled;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        startVerification(event.getPlayer());
    }

    public void startVerification(Player player) {
        if (!enabled) return;


        // SecurityManager가 이 IP에 대해 리소스팩 캡챠를 지시한 경우
        if (securityManager.needsResourcePackCaptcha(player.getUniqueId())) {
            // 강제 리소스팩 전송
            UUID requestId = UUID.randomUUID();
            pendingVerifications.put(player.getUniqueId(), requestId);
            try {
                String url = plugin.getConfig().getString("captcha.resource-pack-url", DUMMY_URL);
                String hash = plugin.getConfig().getString("captcha.resource-pack-sha1", "");
                byte[] digest = hash.isBlank() ? (DUMMY_URL.equals(url) ? DUMMY_HASH : null) : java.util.HexFormat.of().parseHex(hash);
                if (digest != null && digest.length != 20) throw new IllegalArgumentException("SHA-1 must contain 40 hexadecimal characters");
                player.setResourcePack(requestId, url, digest,
                        Component.text("봇 방지를 위해 리소스팩을 수락해야 합니다.", NamedTextColor.YELLOW), true);
            } catch (RuntimeException ex) {
                pendingVerifications.remove(player.getUniqueId());
                plugin.getLogger().warning("[L7] 리소스팩 전송 실패: " + ex.getMessage());
                if (securityManager.getModuleManager() != null && securityManager.getModuleManager().getPenalty(com.l7defense.module.DefenseModule.RP_CAPTCHA) == com.l7defense.module.PenaltyAction.NOTIFY) {
                    securityManager.handleViolation(player, com.l7defense.module.DefenseModule.RP_CAPTCHA, "리소스팩 전송 불가 (주의 통과)");
                    securityManager.passResourcePackCaptcha(player);
                } else {
                    player.kick(Component.text("리소스팩 검증을 시작할 수 없습니다. 관리자에게 문의해주세요."));
                    retryIfKickCancelled(player);
                }
                return;
            }

            // 15초 내에 수락/적용 완료 응답이 없으면 킥 (타임아웃)
            // 느린 다운로드를 고려해 현재 구현은 60초까지 기다립니다.
            tasks.later(player, "timeout", 20L * 60L, () -> {
                if (pendingVerifications.remove(player.getUniqueId(), requestId)) {
                    pendingVerifications.remove(player.getUniqueId());
                    if (player.isOnline()) {
                        plugin.getLogger().warning("[L7] 리소스팩 캡챠 타임아웃: " + player.getName());
                        securityManager.handleViolation(player, com.l7defense.module.DefenseModule.RP_CAPTCHA, "리소스팩 캡챠 타임아웃");
                        if (securityManager.getModuleManager() != null && securityManager.getModuleManager().getPenalty(com.l7defense.module.DefenseModule.RP_CAPTCHA) == com.l7defense.module.PenaltyAction.NOTIFY) {
                            securityManager.passResourcePackCaptcha(player);
                        } else {
                            securityManager.offerCaptchaRetry(player);
                            player.kick(Component.text("리소스팩 다운로드에 실패했습니다. (타임아웃)\n\n서버 리소스팩 설정을 [사용]으로 변경 후 재접속해주세요.", NamedTextColor.RED));
                            retryIfKickCancelled(player);
                        }
                    }
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (!enabled) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (event.getID().equals(pendingVerifications.get(uuid))) {

            switch (event.getStatus()) {
                case SUCCESSFULLY_LOADED:
                    tasks.cancel(uuid); pendingVerifications.remove(uuid); // 봇 검증 완벽 통과
                    securityManager.passResourcePackCaptcha(player);
                    player.sendMessage(Component.text("봇 방지 시스템을 통과했습니다!", NamedTextColor.GREEN));
                    break;
                case DECLINED:
                    tasks.cancel(uuid); pendingVerifications.remove(uuid); // 봇이거나 거부한 유저 차단
                    securityManager.handleViolation(player, com.l7defense.module.DefenseModule.RP_CAPTCHA, "리소스팩 캡챠 거부/실패");
                    if (securityManager.getModuleManager() != null && securityManager.getModuleManager().getPenalty(com.l7defense.module.DefenseModule.RP_CAPTCHA) == com.l7defense.module.PenaltyAction.NOTIFY) {
                        securityManager.passResourcePackCaptcha(player);
                    } else {
                        player.kick(Component.text("서버 접속을 위해 리소스팩 수락이 필수입니다.", NamedTextColor.RED));
                        retryIfKickCancelled(player);
                    }
                    break;
                case FAILED_DOWNLOAD:
                case INVALID_URL:
                case FAILED_RELOAD:
                case DISCARDED:
                    tasks.cancel(uuid);
                    pendingVerifications.remove(uuid);
                    securityManager.handleViolation(player, com.l7defense.module.DefenseModule.RP_CAPTCHA, "리소스팩 다운로드/적용 실패");
                    if (securityManager.getModuleManager() != null && securityManager.getModuleManager().getPenalty(com.l7defense.module.DefenseModule.RP_CAPTCHA) == com.l7defense.module.PenaltyAction.NOTIFY) {
                        securityManager.passResourcePackCaptcha(player);
                    } else {
                        securityManager.offerCaptchaRetry(player);
                        player.kick(Component.text("리소스팩 다운로드/적용에 실패했습니다. 잠시 후 다시 시도해주세요.", NamedTextColor.RED));
                        retryIfKickCancelled(player);
                    }
                    break;
                case DOWNLOADED:
                case ACCEPTED:
                    // 수락은 했으나 아직 다운로드/적용 중. 타이머는 계속 돌아가야 하므로 다시 대기 상태로 넣진 않음.
                    // 위 taskId 취소 로직을 약간 우회해야 함: ACCEPTED일 때는 타이머를 끄지 않게 설계해야 함.
                    // 구현 수정: ACCEPTED 시에는 맵에 다시 넣습니다.
                    // ACCEPTED는 아무것도 취소/제거하지 않고 넘어갑니다.
                    break;
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pendingVerifications.remove(id);
        tasks.cancel(id);
    }

    @Override public void close() { tasks.close(); pendingVerifications.clear(); }

    private void retryIfKickCancelled(Player player) {
        if (!player.isOnline()) return;
        tasks.later(player, "retry", 20L, () -> {
            if (player.isOnline()) startVerification(player);
        });
    }
}
