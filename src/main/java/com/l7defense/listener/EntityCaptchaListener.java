package com.l7defense.listener;
import com.l7defense.util.IpUtils;

import com.l7defense.manager.SecurityManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 3D 물리 엔티티 캡챠 (Moving Entity Captcha).
 * <p>
 * GUI 패킷을 파싱하는 똑똑한 봇(Mineflayer)을 막기 위해,
 * 플레이어 시야 근처에 투명한 아머스탠드(초록색 블록을 쓴 형태)를 소환합니다.
 * 이 아머스탠드를 정확하게 조준하고 타격(Attack)해야 통과합니다.
 */
public final class EntityCaptchaListener implements Listener {

    private final Plugin plugin;
    private final SecurityManager securityManager;
    private final boolean enabled;
    
    // 유저 UUID -> 생성된 캡챠 아머스탠드 UUID
    private final ConcurrentHashMap<UUID, UUID> captchaEntities = new ConcurrentHashMap<>();

    public EntityCaptchaListener(Plugin plugin, SecurityManager securityManager, boolean enabled) {
        this.plugin = plugin;
        this.securityManager = securityManager;
        this.enabled = enabled;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        
        Player player = event.getPlayer();
        String ip = IpUtils.getIp(player);

        // SecurityManager가 3D 캡챠를 지시했거나 공격 상태일 때 랜덤 샘플링
        if (securityManager.needsEntityCaptcha(ip)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> spawnCaptchaEntity(player), 20L);
        }
    }

    public void spawnCaptchaEntity(Player player) {
        if (!player.isOnline()) return;
        Location loc = player.getLocation().add(0, 1.5, 0).add(player.getLocation().getDirection().multiply(2.0));
        
        ArmorStand stand = (ArmorStand) player.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.getEquipment().setHelmet(new ItemStack(Material.LIME_CONCRETE));
        stand.customName(Component.text("클릭하여 봇 방지 통과", NamedTextColor.GREEN));
        stand.setCustomNameVisible(true);

        captchaEntities.put(player.getUniqueId(), stand.getUniqueId());
        player.sendMessage(Component.text("눈앞의 초록색 블록(엔티티)을 좌클릭하여 타격하세요!", NamedTextColor.RED));

        // 15초 제한 타이머
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (captchaEntities.containsKey(player.getUniqueId())) {
                UUID standId = captchaEntities.remove(player.getUniqueId());
                if (standId != null) {
                    org.bukkit.entity.Entity e = Bukkit.getEntity(standId);
                    if (e != null) e.remove();
                }
                
                if (player.isOnline()) {
                    String ip = IpUtils.getIp(player);
                    securityManager.blockIp(ip, "3D 캡챠 타임아웃");
                    player.kick(Component.text("캡챠 인증 시간 초과. 다시 접속해주세요.", NamedTextColor.RED));
                }
            }
        }, 20L * 15L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!enabled) return;
        
        if (event.getDamager() instanceof Player player) {
            UUID targetId = captchaEntities.get(player.getUniqueId());
            
            // 때린 엔티티가 플레이어에게 할당된 캡챠 엔티티인지 확인
            if (targetId != null && event.getEntity().getUniqueId().equals(targetId)) {
                event.setCancelled(true);
                
                String ip = IpUtils.getIp(player);
                securityManager.passEntityCaptcha(ip);
                
                player.sendMessage(Component.text("봇 방지 시스템을 통과했습니다!", NamedTextColor.GREEN));
                
                captchaEntities.remove(player.getUniqueId());
                event.getEntity().remove();
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID standId = captchaEntities.remove(event.getPlayer().getUniqueId());
        if (standId != null) {
            org.bukkit.entity.Entity e = Bukkit.getEntity(standId);
            if (e != null) e.remove();
        }
    }
}
