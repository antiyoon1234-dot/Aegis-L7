package com.l7defense.listener;
import com.l7defense.util.IpUtils;

import com.l7defense.manager.SecurityManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.Random;

/**
 * 인게임 인터랙티브 GUI 캡챠.
 * <p>
 * Rejoin(소프트 캡챠)을 뚫고 들어오는 지능형 봇을 막기 위해,
 * 로그인 직후 인벤토리 GUI를 띄워 특정 색상의 유리를 클릭하도록 유도합니다.
 */
public final class CaptchaListener implements Listener {

    private final Plugin plugin;
    private final SecurityManager securityManager;
    private final Component captchaTitle = Component.text("봇 방지: 초록색 유리를 클릭하세요!", NamedTextColor.RED);
    private final Random random = new Random();

    public CaptchaListener(Plugin plugin, SecurityManager securityManager) {
        this.plugin = plugin;
        this.securityManager = securityManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String ip = IpUtils.getIp(player);

        // SecurityManager에서 이 IP/유저가 GUI 캡챠 대상인지 확인
        if (securityManager.needsGuiCaptcha(ip)) {
            // 약간의 딜레이 후 GUI 오픈 (로딩 시간 확보)
            Bukkit.getScheduler().runTaskLater(plugin, () -> openCaptcha(player), 20L);
            
            // 15초 내 미해결 시 강퇴 스케줄러
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (securityManager.needsGuiCaptcha(ip) && player.isOnline()) {
                    player.kick(Component.text("캡챠 인증 시간 초과. 다시 접속해주세요.", NamedTextColor.RED));
                }
            }, 20L * 15L);
        }
    }

    public void openCaptcha(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, captchaTitle);
        int greenSlot = random.nextInt(27);

        ItemStack redPane = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta redMeta = redPane.getItemMeta();
        redMeta.displayName(Component.text("클릭하지 마세요!", NamedTextColor.RED));
        redPane.setItemMeta(redMeta);

        ItemStack greenPane = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta greenMeta = greenPane.getItemMeta();
        greenMeta.displayName(Component.text("여기를 클릭하여 인증!", NamedTextColor.GREEN));
        greenPane.setItemMeta(greenMeta);

        for (int i = 0; i < 27; i++) {
            inv.setItem(i, (i == greenSlot) ? greenPane : redPane);
        }
        player.openInventory(inv);
    }

    // ── 캡챠 통과 확인 로직 ──
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String ip = IpUtils.getIp(player);

        if (securityManager.needsGuiCaptcha(ip)) {
            event.setCancelled(true); // 아이템 이동 방지
            
            if (event.getView().title().equals(captchaTitle)) {
                ItemStack clicked = event.getCurrentItem();
                if (clicked != null && clicked.getType() == Material.GREEN_STAINED_GLASS_PANE) {
                    // 캡챠 통과
                    securityManager.passGuiCaptcha(ip);
                    player.closeInventory();
                    player.sendMessage(Component.text("인증이 완료되었습니다. 환영합니다!", NamedTextColor.GREEN));
                } else if (clicked != null && clicked.getType() == Material.RED_STAINED_GLASS_PANE) {
                    player.kick(Component.text("잘못된 아이템을 클릭했습니다. 다시 시도하세요.", NamedTextColor.RED));
                }
            }
        }
    }

    // ── 캡챠 도중 행동 금지 (이동, 채팅, 인벤닫기 등) ──
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        String ip = IpUtils.getIp(player);
        if (securityManager.needsGuiCaptcha(ip)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && securityManager.needsGuiCaptcha(ip)) {
                    openCaptcha(player); // 닫으면 다시 염
                }
            }, 5L);
        }
    }

    private boolean isAnyCaptchaPending(String ip) {
        return securityManager.needsGuiCaptcha(ip) || 
               securityManager.needsResourcePackCaptcha(ip) || 
               securityManager.needsEntityCaptcha(ip) ||
               securityManager.needsMapCaptcha(ip);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        String ip = IpUtils.getIp(event.getPlayer());
        if (isAnyCaptchaPending(ip)) {
            if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        String ip = IpUtils.getIp(event.getPlayer());
        if (isAnyCaptchaPending(ip)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("인증을 먼저 완료해주세요.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
        public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String ip = IpUtils.getIp(event.getPlayer());
        if (isAnyCaptchaPending(ip)) {
            // 관리자 명령어 예외 처리
            if (event.getMessage().toLowerCase().startsWith("/l7def")) {
                return; // 허용
            }
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("캡챠 인증을 먼저 완료해주세요.", NamedTextColor.RED));
        }
    }
}