package com.l7defense.listener;
import com.l7defense.util.IpUtils;

import com.l7defense.manager.SecurityManager;
import com.l7defense.module.DefenseModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Random;

public final class MapCaptchaListener implements Listener {

    private final Plugin plugin;
    private final SecurityManager securityManager;
    private final ConcurrentHashMap<UUID, String> activeCaptchas = new ConcurrentHashMap<>();
    private final Random random = new Random();
    
    private MapView captchaMapView;

    public MapCaptchaListener(Plugin plugin, SecurityManager securityManager) {
        this.plugin = plugin;
        this.securityManager = securityManager;
    }

    public void triggerMapCaptcha(Player player) {
        String captchaText = String.format("%04d", random.nextInt(10000));
        activeCaptchas.put(player.getUniqueId(), captchaText);
        org.bukkit.map.MapView view = getOrCreateCaptchaMap(player.getWorld());
        org.bukkit.inventory.ItemStack mapItem = new org.bukkit.inventory.ItemStack(org.bukkit.Material.FILLED_MAP);
        org.bukkit.inventory.meta.MapMeta meta = (org.bukkit.inventory.meta.MapMeta) mapItem.getItemMeta();
        if (meta != null) {
            meta.setMapView(view);
            mapItem.setItemMeta(meta);
        }
        player.getInventory().setHeldItemSlot(0);
        player.getInventory().setItemInMainHand(mapItem);
        player.updateInventory();
        player.sendMessage(net.kyori.adventure.text.Component.text("[L7] 캡챠: 손에 든 지도를 보고 코드를 채팅에 입력하세요. (30초)", net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    private MapView getOrCreateCaptchaMap(org.bukkit.World world) {
        if (captchaMapView == null) {
            captchaMapView = Bukkit.createMap(world);
            captchaMapView.getRenderers().clear();
            captchaMapView.addRenderer(new MapRenderer() {
                @Override
                public void render(MapView map, MapCanvas canvas, Player p) {
                    String code = activeCaptchas.get(p.getUniqueId());
                    if (code != null) {
                        for (int i = 0; i < 128; i++) for (int j = 0; j < 128; j++) canvas.setPixel(i, j, (byte) 34);
                        canvas.drawText(20, 50, MinecraftFont.Font, "CODE: " + code);
                    } else {
                        for (int i = 0; i < 128; i++) for (int j = 0; j < 128; j++) canvas.setPixel(i, j, (byte) 34);
                    }
                }
            });
        }
        return captchaMapView;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String ip = IpUtils.getIp(player);
        if (securityManager.needsMapCaptcha(ip)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && securityManager.needsMapCaptcha(ip)) {
                    triggerMapCaptcha(player);
                }
            }, 20L);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String expected = activeCaptchas.get(player.getUniqueId());
        if (expected != null) {
            event.setCancelled(true);
            if (event.getMessage().trim().equalsIgnoreCase(expected)) {
                activeCaptchas.remove(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> player.getInventory().setItemInMainHand(new ItemStack(Material.AIR)));
                String ip = IpUtils.getIp(player);
                securityManager.passMapCaptcha(ip);
                player.sendMessage(Component.text("검증 성공! 정상적으로 플레이가 가능합니다.", NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("틀렸습니다. 지도를 다시 확인하세요.", NamedTextColor.RED));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        activeCaptchas.remove(event.getPlayer().getUniqueId());
    }
}