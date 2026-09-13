package com.l7defense.listener;
import com.l7defense.util.IpUtils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.l7defense.manager.SecurityManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 포스트-로그인 패킷 스팸(Server Crusher) 방어 리스너.
 * <p>
 * 캡챠를 뚫고 들어온 악성 봇이 인벤토리 클릭, 상호작용 등을 
 * 초당 수십/수백 번 전송하여 서버 틱을 저하시키는 것을 방지합니다.
 */
public final class PacketSpamListener implements Listener {

    private final SecurityManager securityManager;
    private final boolean enabled;
    private final int maxActionsPerSecond;
    
    // 1초 단위로 플레이어의 행동 횟수를 캐싱
    private final Cache<String, AtomicInteger> actionCache;

    public PacketSpamListener(SecurityManager securityManager, boolean enabled, int maxActionsPerSecond) {
        this.securityManager = securityManager;
        this.enabled = enabled;
        this.maxActionsPerSecond = maxActionsPerSecond;

        this.actionCache = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.SECONDS)
                .maximumSize(5000)
                .build();
    }

    private boolean checkSpam(Player player) {
        if (!enabled || player.hasPermission("l7defense.admin")) return false;

        String ip = IpUtils.getIp(player);
        AtomicInteger count = actionCache.get(ip, k -> new AtomicInteger(0));
        
        if (count.incrementAndGet() > maxActionsPerSecond) {
            // 패킷 플러드 감지 -> 즉시 킥 및 IP 차단
            securityManager.blockIp(ip, "인게임 패킷 스팸 (CPS 초과)");
            player.kick(Component.text("비정상적인 속도의 패킷이 감지되었습니다.", NamedTextColor.RED));
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            if (checkSpam(player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (checkSpam(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
