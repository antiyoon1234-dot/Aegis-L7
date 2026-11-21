package com.l7defense.listener;

import com.l7defense.manager.SecurityManager;
import com.l7defense.module.DefenseModule;
import com.l7defense.util.PlayerTasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MapCaptchaListener implements Listener, AutoCloseable {
    private record Challenge(String answer, UUID token, MapView map, ItemStack saved, int heldSlot) {}
    private final Plugin plugin;
    private final SecurityManager securityManager;
    private final PlayerTasks tasks;
    private final ConcurrentHashMap<UUID, Challenge> activeCaptchas = new ConcurrentHashMap<>();
    private final Random random = new java.security.SecureRandom();
    private final Object mapLock = new Object();
    private final Deque<MapView> freeMaps = new ArrayDeque<>();
    private final Set<MapView> allMaps = new HashSet<>();
    private final NamespacedKey itemKey;

    public MapCaptchaListener(Plugin plugin, SecurityManager securityManager) {
        this.plugin = plugin;
        this.securityManager = securityManager;
        this.tasks = new PlayerTasks(plugin);
        this.itemKey = new NamespacedKey(plugin, "captcha-map");
        synchronized (mapLock) {
            for (int id : plugin.getConfig().getIntegerList("internal.captcha-map-ids")) {
                MapView map = Bukkit.getMap(id);
                if (map != null && allMaps.size() < 256 && allMaps.add(map)) freeMaps.add(map);
            }
        }
    }

    public void triggerMapCaptcha(Player player) {
        UUID id = player.getUniqueId();
        if (!player.isOnline() || !securityManager.needsMapCaptcha(id) || activeCaptchas.containsKey(id)) return;
        MapView map;
        synchronized (mapLock) {
            map = freeMaps.pollFirst();
            if (map == null) {
                if (allMaps.size() >= 256) {
                    if (securityManager.getModuleManager() != null && securityManager.getModuleManager().getPenalty(DefenseModule.MAP_CAPTCHA) == com.l7defense.module.PenaltyAction.NOTIFY) {
                        securityManager.handleViolation(player, DefenseModule.MAP_CAPTCHA, "지도 캡챠 슬롯 포화 (주의 통과)");
                        securityManager.passMapCaptcha(player);
                    } else {
                        player.kick(Component.text("캡챠 서버가 혼잡합니다. 잠시 후 다시 접속해주세요."));
                    }
                    return;
                }
                map = Bukkit.createMap(player.getWorld());
                allMaps.add(map);
                plugin.getConfig().set("internal.captcha-map-ids", allMaps.stream().map(MapView::getId).toList());
                plugin.saveConfig();
            }
        }
        clearRenderers(map);
        String answer = String.format(Locale.ROOT, "%04d", random.nextInt(10000));
        // 지도마다 정답이 고정되어 공유 캔버스/컨텍스트별 Player 참조가 누적되지 않습니다.
        map.addRenderer(new MapRenderer(false) {
            private boolean drawn;
            @Override public void render(MapView view, MapCanvas canvas, Player viewer) {
                if (drawn) return;
                for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) canvas.setPixel(x, y, (byte) 34);
                canvas.drawText(20, 50, MinecraftFont.Font, "CODE: " + answer);
                drawn = true;
            }
        });
        ItemStack saved = player.getInventory().getItem(0);
        Challenge challenge = new Challenge(answer, UUID.randomUUID(), map,
                saved == null ? null : saved.clone(), player.getInventory().getHeldItemSlot());
        activeCaptchas.put(id, challenge);
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(map);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, challenge.token.toString());
        item.setItemMeta(meta);
        player.getInventory().setHeldItemSlot(0);
        player.getInventory().setItem(0, item);
        player.sendMessage(Component.text("[L7] 캡챠: 손에 든 지도를 보고 코드를 채팅에 입력하세요. (30초)", NamedTextColor.RED));
        tasks.later(player, "timeout", 600L, () -> {
            if (activeCaptchas.get(id) != challenge) return;
            cleanup(player);
            // 강제 밴 처리: 전역 설정 변경 없이 현재 모듈 처벌 정책을 사용합니다.
            securityManager.handleViolation(player, DefenseModule.MAP_CAPTCHA, "캡챠 30초 타임아웃 미인증");
            plugin.getLogger().warning("[L7 Defense] " + player.getName() + " 캡챠 30초 미인증 처리됨.");
            if (securityManager.getModuleManager() != null && securityManager.getModuleManager().getPenalty(DefenseModule.MAP_CAPTCHA) == com.l7defense.module.PenaltyAction.NOTIFY) {
                securityManager.passMapCaptcha(player);
            } else if (player.isOnline()) {
                tasks.later(player, "retry", 20L, () -> {
                    if (player.isOnline()) triggerMapCaptcha(player);
                });
            }
        }); // 30초 후 확인
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (securityManager.needsMapCaptcha(player.getUniqueId())) tasks.later(player, "open", 20L, () -> triggerMapCaptcha(player));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (player == null || event.getMessage() == null) return;
        var session = securityManager.getSessions() != null ? securityManager.getSessions().get(player) : null;
        Challenge expected = session == null ? null : activeCaptchas.get(session.id());
        if (expected == null) return;
        event.setCancelled(true);
        String message = event.getMessage().trim();
        securityManager.getSessions().submit(player, "map-answer", () -> {
            if (activeCaptchas.get(session.id()) != expected) return;
            if (message.equals(expected.answer)) {
                cleanup(player);
                securityManager.passMapCaptcha(player);
                player.sendMessage(Component.text("검증 성공! 정상적으로 플레이가 가능합니다.", NamedTextColor.GREEN));
            } else player.sendMessage(Component.text("틀렸습니다. 지도를 다시 확인하세요.", NamedTextColor.RED));
        });
    }

    private boolean isCaptchaItem(ItemStack item, Challenge challenge) {
        return item != null && item.hasItemMeta() && challenge.token.toString().equals(
                item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING));
    }

    private void cleanup(Player player) {
        tasks.cancel(player.getUniqueId());
        Challenge challenge = activeCaptchas.remove(player.getUniqueId());
        if (challenge == null) return;
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (isCaptchaItem(player.getInventory().getItem(slot), challenge)) player.getInventory().setItem(slot, null);
        }
        if (challenge.saved != null) {
            ItemStack current = player.getInventory().getItem(0);
            if (current == null || current.getType().isAir()) player.getInventory().setItem(0, challenge.saved);
            else player.getInventory().addItem(challenge.saved).values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
        player.getInventory().setHeldItemSlot(challenge.heldSlot);
        clearRenderers(challenge.map);
        synchronized (mapLock) {
            freeMaps.addLast(challenge.map);
        }
    }

    private void clearRenderers(MapView map) { new ArrayList<>(map.getRenderers()).forEach(map::removeRenderer); }
    private boolean active(Player p) { return activeCaptchas.containsKey(p.getUniqueId()); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (active(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { if (active(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onHeld(PlayerItemHeldEvent e) { if (active(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onInteract(PlayerInteractEvent e) { if (active(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onEntityInteract(PlayerInteractEntityEvent e) { if (active(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && active(p)) e.setCancelled(true);
    }
    @EventHandler public void onDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && active(p)) e.setCancelled(true);
    }
    @EventHandler public void onDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        Challenge challenge = activeCaptchas.get(e.getEntity().getUniqueId());
        if (challenge == null) return;
        e.getDrops().removeIf(item -> isCaptchaItem(item, challenge));
        if (!e.getKeepInventory() && challenge.saved != null) e.getDrops().add(challenge.saved.clone());
        cleanup(e.getEntity());
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) { cleanup(e.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        if (securityManager.needsMapCaptcha(e.getPlayer().getUniqueId())) tasks.later(e.getPlayer(), "open", 20L, () -> triggerMapCaptcha(e.getPlayer()));
    }
    @Override public void close() {
        tasks.close();
        for (UUID id : new ArrayList<>(activeCaptchas.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) cleanup(player);
        }
        activeCaptchas.clear();
        synchronized (mapLock) {
            allMaps.forEach(this::clearRenderers);
            freeMaps.clear();
            allMaps.clear();
        }
    }
}
