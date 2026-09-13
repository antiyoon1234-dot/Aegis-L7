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

/**
 * 지도(Map) 캔버스 기반 OCR 방지 캡챠 (v1.6).
 * <p>
 * 플레이어에게 지도를 들려주고 지도 이미지 상에 렌더링된 무작위 문자를 채팅으로 입력하게 합니다.
 * 일반적인 봇들은 마인크래프트 지도 픽셀 패킷을 파싱하고 OCR 처리할 능력이 없어 걸러집니다.
 */
public final class MapCaptchaListener implements Listener {

    private final Plugin plugin;
    private final SecurityManager securityManager;
    private final ConcurrentHashMap<UUID, String> activeCaptchas = new ConcurrentHashMap<>();
    private final Random random = new Random();
    
    // 메모리 누수(Map ID 고갈) 방지용 싱글톤 가상 맵 뷰
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
        player.updateInventory(); // 1.20+ 강제 업데이트
        player.sendMessage(net.kyori.adventure.text.Component.text("[L7] 캡챠: 손에 든 지도를 보고 코드를 채팅에 입력하세요. (30초)", net.kyori.adventure.text.format.NamedTextColor.RED));
    }
        player.getInventory().setItemInMainHand(mapItem);
        player.sendMessage(net.kyori.adventure.text.Component.text("[L7] 테스트: 손에 든 지도를 보고 코드를 채팅에 입력하세요.", net.kyori.adventure.text.format.NamedTextColor.RED));
    }
    private MapView getOrCreateCaptchaMap(org.bukkit.World world) {
        if (captchaMapView == null) {
            captchaMapView = Bukkit.createMap(world);
            captchaMapView.getRenderers().clear();
            captchaMapView.addRenderer(new MapRenderer() {
                @Override
                public void render(MapView map, MapCanvas canvas, Player p) {
                    // 유저별 고유 캡챠 렌더링
                    String code = activeCaptchas.get(p.getUniqueId());
                    if (code != null) {
                        for (int i = 0; i < 128; i++) for (int j = 0; j < 128; j++) canvas.setPixel(i, j, (byte) 34); // 회색 배경
                        canvas.drawText(20, 50, MinecraftFont.Font, "CODE: " + code);
                    } else {
                        // 캡챠 대상이 아닌 유저가 지도를 볼 경우
                        canvas.drawText(20, 50, MinecraftFont.Font, "NO CAPTCHA");
                    }
                }
            });
        }
        return captchaMapView;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!securityManager.getModuleManager().isEnabled(DefenseModule.MAP_CAPTCHA)) return;

        Player player = event.getPlayer();
        String ip = IpUtils.getIp(player);

        // 캡챠 대상으로 지정된 경우
        if (securityManager.needsMapCaptcha(ip)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;

                String captchaText = generateRandomString(5);
                activeCaptchas.put(player.getUniqueId(), captchaText);

                // 지도 메모리 고갈을 막기 위해 단 1개의 뷰를 공유해서 유저별로 다르게 그림
                MapView view = getOrCreateCaptchaMap(player.getWorld());

                ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
                MapMeta meta = (MapMeta) mapItem.getItemMeta();
                if (meta != null) {
                    meta.setMapView(view);
                    mapItem.setItemMeta(meta);
                }

                player.getInventory().setItemInMainHand(mapItem);
                player.sendMessage(Component.text("[L7] 봇 방지: 손에 든 지도를 보고 코드를 채팅에 입력하세요!", NamedTextColor.RED));

                // 20초 타임아웃
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (activeCaptchas.containsKey(player.getUniqueId()) && player.isOnline()) {
                        activeCaptchas.remove(player.getUniqueId());
                        securityManager.handleViolation(player, DefenseModule.MAP_CAPTCHA, "지도 캡챠 시간 초과");
                    }
                }, 20L * 20L);
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
                // 성공
                activeCaptchas.remove(player.getUniqueId());
                player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                String ip = IpUtils.getIp(player);
                securityManager.passMapCaptcha(ip);
                player.sendMessage(Component.text("검증 성공! 정상적으로 플레이가 가능합니다.", NamedTextColor.GREEN));
            } else {
                // 실패
                Bukkit.getScheduler().runTask(plugin, () -> {
                    securityManager.handleViolation(player, DefenseModule.MAP_CAPTCHA, "지도 캡챠 코드 오입력");
                });
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        activeCaptchas.remove(event.getPlayer().getUniqueId());
    }

    private String generateRandomString(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 혼동하기 쉬운 O, 0, 1, I 제외
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
