package com.l7defense.listener;

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
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 포스트-로그인 패킷 스팸(Server Crusher) 방어 리스너.
 * <p>
 * 캡챠를 뚫고 들어온 악성 봇이 인벤토리 클릭, 상호작용 등을
 * 초당 수십/수백 번 전송하여 서버 틱을 저하시키는 것을 방지합니다.
 */
public final class PacketSpamListener implements Listener, AutoCloseable {

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
        if (player == null) return false;
        boolean isSpamEnabled = securityManager.getModuleManager() != null ? securityManager.getModuleManager().isEnabled(com.l7defense.module.DefenseModule.PACKET_SPAM) : enabled;
        if (!isSpamEnabled || player.hasPermission("l7defense.admin")) return false;

        AtomicInteger count = actionCache.get(player.getUniqueId().toString(), k -> new AtomicInteger(0));
        if (count != null) {
            int current = count.incrementAndGet();
            if (current > maxActionsPerSecond) {
                // 초과 시점 1회만 알림 발송하여 관리자 채팅 도배 방지
                if (current == maxActionsPerSecond + 1) {
                    securityManager.handleViolation(player, com.l7defense.module.DefenseModule.PACKET_SPAM, "인게임 패킷 스팸 (CPS 초과: " + maxActionsPerSecond + "/s)");
                }
                return true;
            }
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
        // 압력판/농지 밟기(PHYSICAL) 및 보조손(OFF_HAND) 중복 이벤트로 인한 2배 오탐 방지
        if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL) return;
        if (event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND) return;
        if (checkSpam(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        actionCache.invalidate(event.getPlayer().getUniqueId().toString());
    }

    @Override
    public void close() {
        actionCache.invalidateAll();
    }
}
