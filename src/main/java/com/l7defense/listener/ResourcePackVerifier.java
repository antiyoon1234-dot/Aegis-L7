package com.l7defense.listener;
import com.l7defense.util.IpUtils;

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
public final class ResourcePackVerifier implements Listener {

    private final Plugin plugin;
    private final SecurityManager securityManager;
    private final boolean enabled;
    
    // 타임아웃 킥 관리를 위한 작업 맵
    private final ConcurrentHashMap<UUID, Integer> pendingVerifications = new ConcurrentHashMap<>();

    private static final String DUMMY_URL = "https://github.com/LOOHP/ServerResourcepackDummy/raw/master/dummy.zip";
    // Dummy zip hash (임의 적용, 봇은 검사조차 못함)
    private static final byte[] DUMMY_HASH = new byte[20]; 

    public ResourcePackVerifier(Plugin plugin, SecurityManager securityManager, boolean enabled) {
        this.plugin = plugin;
        this.securityManager = securityManager;
        this.enabled = enabled;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        
        Player player = event.getPlayer();
        String ip = IpUtils.getIp(player);

        // SecurityManager가 이 IP에 대해 리소스팩 캡챠를 지시한 경우
        if (securityManager.needsResourcePackCaptcha(ip)) {
            // 강제 리소스팩 전송
            player.setResourcePack(DUMMY_URL, DUMMY_HASH, Component.text("봇 방지를 위해 리소스팩을 수락해야 합니다.", NamedTextColor.YELLOW), true);
            
            // 15초 내에 수락/적용 완료 응답이 없으면 킥 (타임아웃)
            int taskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (pendingVerifications.containsKey(player.getUniqueId()) && player.isOnline()) {
                    pendingVerifications.remove(player.getUniqueId());
                    securityManager.blockIp(ip, "리소스팩 캡챠 타임아웃");
                    player.kick(Component.text("리소스팩 검증에 실패했습니다. (타임아웃)", NamedTextColor.RED));
                }
            }, 20L * 15L).getTaskId();
            
            pendingVerifications.put(player.getUniqueId(), taskId);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (!enabled) return;
        
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        if (pendingVerifications.containsKey(uuid)) {
            Integer taskId = pendingVerifications.get(uuid); String ip = IpUtils.getIp(player);

            switch (event.getStatus()) {
                case SUCCESSFULLY_LOADED:
                    if (taskId != null) Bukkit.getScheduler().cancelTask(taskId); pendingVerifications.remove(uuid); // 봇 검증 완벽 통과
                    securityManager.passResourcePackCaptcha(ip);
                    player.sendMessage(Component.text("봇 방지 시스템을 통과했습니다!", NamedTextColor.GREEN));
                    break;
                case DECLINED:
                case FAILED_DOWNLOAD:
                    if (taskId != null) Bukkit.getScheduler().cancelTask(taskId); pendingVerifications.remove(uuid); // 봇이거나 거부한 유저 차단
                    securityManager.blockIp(ip, "리소스팩 캡챠 거부/실패");
                    player.kick(Component.text("서버 접속을 위해 리소스팩 수락이 필수입니다.", NamedTextColor.RED));
                    break;
                case ACCEPTED:
                    // 수락은 했으나 아직 다운로드/적용 중. 타이머는 계속 돌아가야 하므로 다시 대기 상태로 넣진 않음.
                    // 위 taskId 취소 로직을 약간 우회해야 함: ACCEPTED일 때는 타이머를 끄지 않게 설계해야 함.
                    // 구현 수정: ACCEPTED 시에는 맵에 다시 넣습니다.
                    // ACCEPTED는 아무것도 취소/제거하지 않고 넘어갑니다.`n                    break;
            }
        }
    }
}
