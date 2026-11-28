package com.l7defense.listener;

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
public final class EntityCaptchaListener implements Listener, AutoCloseable {

    private final Plugin plugin;
    private final SecurityManager securityManager;
    private final boolean enabled;

    // 유저 UUID -> 생성된 캡챠 아머스탠드 UUID
    private final java.util.Map<UUID, ArmorStand> captchaEntities = new ConcurrentHashMap<>();
    private final com.l7defense.util.PlayerTasks tasks;

    public EntityCaptchaListener(Plugin plugin, SecurityManager securityManager, boolean enabled) {
        this.plugin = plugin;
        this.tasks = new com.l7defense.util.PlayerTasks(plugin);
        this.securityManager = securityManager;
        this.enabled = enabled;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled) return;

        Player player = event.getPlayer();


        // SecurityManager가 3D 캡챠를 지시했거나 공격 상태일 때 랜덤 샘플링
        if (securityManager.needsEntityCaptcha(player.getUniqueId())) {
            tasks.later(player, "open", 20L, () -> spawnCaptchaEntity(player));
        }
    }

    public void spawnCaptchaEntity(Player player) {
        if (!player.isOnline() || !securityManager.needsEntityCaptcha(player.getUniqueId())) return;
        remove(player.getUniqueId());

        // 아머스탠드 헬멧(초록 블록)은 발 위치 +1.7 높이에 렌더링되므로 발 높이를 플레이어 발 기준(y)으로 맞추고
        // 정면 수평 방향으로 벽 충돌을 피해 1.2~2.2블록 앞에 배치하여 천장/벽 끼임을 방지합니다.
        org.bukkit.util.Vector dir = player.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) {
            dir = new org.bukkit.util.Vector(0, 0, 1);
        } else {
            dir.normalize();
        }
        Location loc = player.getLocation().clone();
        for (double step = 0.5; step <= 2.2; step += 0.5) {
            Location candidate = player.getLocation().clone().add(dir.clone().multiply(step));
            if (candidate.getBlock().getType().isSolid() || candidate.clone().add(0, 1, 0).getBlock().getType().isSolid()) {
                break;
            }
            loc = candidate;
        }

        ArmorStand stand;
        try {
            stand = (ArmorStand) player.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[L7] 3D 캡챠 생성 실패: " + ex.getMessage());
            retryUnavailable(player);
            return;
        }
        if (!stand.isValid()) {
            stand.remove();
            retryUnavailable(player);
            return;
        }
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setInvulnerable(false);
        stand.setPersistent(false);
        stand.setCollidable(false);
        try {
            stand.addDisabledSlots(
                    org.bukkit.inventory.EquipmentSlot.HEAD,
                    org.bukkit.inventory.EquipmentSlot.CHEST,
                    org.bukkit.inventory.EquipmentSlot.LEGS,
                    org.bukkit.inventory.EquipmentSlot.FEET,
                    org.bukkit.inventory.EquipmentSlot.HAND,
                    org.bukkit.inventory.EquipmentSlot.OFF_HAND
            );
        } catch (Throwable ignored) {}
        stand.getEquipment().setHelmet(new ItemStack(Material.LIME_CONCRETE));
        stand.customName(Component.text("클릭하여 봇 방지 통과", NamedTextColor.GREEN));
        stand.setCustomNameVisible(true);

        // 다른 유저 화면에 타인의 캡챠 엔티티가 보이지 않도록 숨김 처리
        try {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.getUniqueId().equals(player.getUniqueId())) {
                    other.hideEntity(plugin, stand);
                }
            }
        } catch (Throwable ignored) {}

        captchaEntities.put(player.getUniqueId(), stand);
        player.sendMessage(Component.text("눈앞의 초록색 블록(엔티티)을 좌클릭하여 타격하세요!", NamedTextColor.RED));

        // 15초 제한 타이머
        tasks.later(player, "timeout", 20L * 15L, () -> {
            if (captchaEntities.get(player.getUniqueId()) != stand) return;
            remove(player.getUniqueId());
            securityManager.handleViolation(player, com.l7defense.module.DefenseModule.ENTITY_CAPTCHA, "3D 캡챠 타임아웃");
            if (securityManager.getModuleManager() != null && securityManager.getModuleManager().getPenalty(com.l7defense.module.DefenseModule.ENTITY_CAPTCHA) == com.l7defense.module.PenaltyAction.NOTIFY) {
                securityManager.passEntityCaptcha(player);
            } else if (player.isOnline()) {
                tasks.later(player, "retry", 20L, () -> {
                    if (player.isOnline()) spawnCaptchaEntity(player);
                });
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!enabled) return;

        if (event.getDamager() instanceof Player player) {
            ArmorStand target = captchaEntities.get(player.getUniqueId());

            // 때린 엔티티가 플레이어에게 할당된 캡챠 엔티티인지 확인
            if (target != null && event.getEntity().getUniqueId().equals(target.getUniqueId())) {
                event.setCancelled(true);

                securityManager.passEntityCaptcha(player);

                player.sendMessage(Component.text("봇 방지 시스템을 통과했습니다!", NamedTextColor.GREEN));

                remove(player.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        remove(event.getPlayer().getUniqueId());
    }

    private void remove(UUID id) {
        tasks.cancel(id);
        ArmorStand stand = captchaEntities.remove(id);
        if (stand != null) stand.remove();
    }

    private void retryUnavailable(Player player) {
        securityManager.offerCaptchaRetry(player);
        if (securityManager.getModuleManager() != null && securityManager.getModuleManager().getPenalty(com.l7defense.module.DefenseModule.ENTITY_CAPTCHA) == com.l7defense.module.PenaltyAction.NOTIFY) {
            securityManager.handleViolation(player, com.l7defense.module.DefenseModule.ENTITY_CAPTCHA, "3D 캡챠 생성 불가 (주의 통과)");
            securityManager.passEntityCaptcha(player);
        } else {
            player.kick(Component.text("캡챠를 생성할 수 없습니다. 잠시 후 다시 접속해주세요.", NamedTextColor.RED));
            if (player.isOnline()) {
                tasks.later(player, "retry", 20L, () -> {
                    if (player.isOnline()) spawnCaptchaEntity(player);
                });
            }
        }
    }

    @EventHandler
    public void protect(org.bukkit.event.entity.EntityDamageEvent event) {
        if (captchaEntities.containsValue(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler
    public void protectEquipment(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (captchaEntities.containsValue(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler
    public void protectArmorStandManipulate(org.bukkit.event.player.PlayerArmorStandManipulateEvent event) {
        if (captchaEntities.containsValue(event.getRightClicked())) event.setCancelled(true);
    }

    @Override public void close() {
        tasks.close();
        captchaEntities.values().forEach(ArmorStand::remove);
        captchaEntities.clear();
    }
}
