package com.l7defense.listener;

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
import java.util.UUID;

/**
 * 인게임 인터랙티브 GUI 캡챠.
 * <p>
 * Rejoin(소프트 캡챠)을 뚫고 들어오는 지능형 봇을 막기 위해,
 * 로그인 직후 인벤토리 GUI를 띄워 특정 색상의 유리를 클릭하도록 유도합니다.
 */
public final class CaptchaListener implements Listener, AutoCloseable {

    private final Plugin plugin;
    private final SecurityManager securityManager;
    private final Component captchaTitle = Component.text("봇 방지: 초록색 유리를 클릭하세요!", NamedTextColor.RED);
    private final Random random = new java.security.SecureRandom();
    private final java.util.Map<java.util.UUID, Inventory> views = new java.util.concurrent.ConcurrentHashMap<>();
    private final com.l7defense.util.PlayerTasks tasks;
    private boolean closed;

    public CaptchaListener(Plugin plugin, SecurityManager securityManager) {
        this.plugin = plugin;
        this.tasks = new com.l7defense.util.PlayerTasks(plugin);
        this.securityManager = securityManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();


        // SecurityManager에서 이 IP/유저가 GUI 캡챠 대상인지 확인
        if (securityManager.needsGuiCaptcha(player.getUniqueId())) {
            // 약간의 딜레이 후 GUI 오픈 (로딩 시간 확보)
            tasks.later(player, "open", 20L, () -> openCaptcha(player));

            // 15초 내 미해결 시 강퇴 스케줄러

        }
    }

    public void openCaptcha(Player player) {
        if (closed || !player.isOnline() || !securityManager.needsGuiCaptcha(player.getUniqueId())) return;
        if (!views.containsKey(player.getUniqueId())) {
            tasks.later(player, "timeout", 20L * 15L, () -> {
                if (securityManager.needsGuiCaptcha(player.getUniqueId())) {
                    securityManager.offerCaptchaRetry(player);
                    views.remove(player.getUniqueId());
                    if (securityManager.getModuleManager() != null && securityManager.getModuleManager().getPenalty(com.l7defense.module.DefenseModule.GUI_CAPTCHA) == com.l7defense.module.PenaltyAction.NOTIFY) {
                        securityManager.handleViolation(player, com.l7defense.module.DefenseModule.GUI_CAPTCHA, "캡챠 인증 시간 초과");
                        securityManager.passGuiCaptcha(player);
                        tasks.later(player, "close", 1L, player::closeInventory);
                    } else {
                        player.kick(Component.text("캡챠 인증 시간 초과. 다시 접속해주세요.", NamedTextColor.RED));
                        if (player.isOnline()) {
                            tasks.later(player, "retry", 20L, () -> {
                                if (player.isOnline()) openCaptcha(player);
                            });
                        }
                    }
                }
            });
        }
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
        views.put(player.getUniqueId(), inv);
        player.openInventory(inv);
    }

    // ── 캡챠 통과 확인 로직 ──
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;


        if (securityManager.needsGuiCaptcha(player.getUniqueId())) {
            event.setCancelled(true); // 아이템 이동 방지

            if (event.getView().getTopInventory() == views.get(player.getUniqueId())
                    && event.getRawSlot() >= 0 && event.getRawSlot() < event.getView().getTopInventory().getSize()) {
                ItemStack clicked = event.getCurrentItem();
                if (clicked != null && clicked.getType() == Material.GREEN_STAINED_GLASS_PANE) {
                    // 캡챠 통과
                    securityManager.passGuiCaptcha(player);
                    tasks.cancel(player.getUniqueId());
                    views.remove(player.getUniqueId());
                    tasks.later(player, "close", 1L, player::closeInventory);
                    player.sendMessage(Component.text("인증이 완료되었습니다. 환영합니다!", NamedTextColor.GREEN));
                } else if (clicked != null && clicked.getType() == Material.RED_STAINED_GLASS_PANE) {
                    views.remove(player.getUniqueId());
                    tasks.cancel(player.getUniqueId());
                    securityManager.handleViolation(player, com.l7defense.module.DefenseModule.GUI_CAPTCHA, "잘못된 아이템 클릭");
                    if (securityManager.getModuleManager() != null && securityManager.getModuleManager().getPenalty(com.l7defense.module.DefenseModule.GUI_CAPTCHA) == com.l7defense.module.PenaltyAction.NOTIFY) {
                        securityManager.passGuiCaptcha(player);
                        tasks.later(player, "close", 1L, player::closeInventory);
                    } else {
                        player.kick(Component.text("잘못된 아이템을 클릭했습니다. 다시 시도하세요.", NamedTextColor.RED));
                    }
                }
            }
        }
    }

    // ── 캡챠 도중 행동 금지 (이동, 채팅, 인벤닫기 등) ──
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player) || closed) return;

        if (securityManager.needsGuiCaptcha(player.getUniqueId()) && event.getInventory() == views.get(player.getUniqueId())) {
            tasks.later(player, "reopen", 5L, () -> {
                if (player.isOnline() && securityManager.needsGuiCaptcha(player.getUniqueId())) {
                    openCaptcha(player); // 닫으면 다시 염
                }
            });
        }
    }

    private boolean isAnyCaptchaPending(java.util.UUID id) {
        return securityManager.needsGuiCaptcha(id) ||
               securityManager.needsResourcePackCaptcha(id) ||
               securityManager.needsEntityCaptcha(id) ||
               securityManager.needsMapCaptcha(id);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerMove(PlayerMoveEvent event) {

        if (isAnyCaptchaPending(event.getPlayer().getUniqueId())) {
            if (event.getTo() != null && (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        var session = securityManager.getSessions() != null ? securityManager.getSessions().get(player) : null;
        if (session != null && isAnyCaptchaPending(session.id())) {
            event.setCancelled(true);
            if (!securityManager.needsMapCaptcha(session.id())) {
                securityManager.getSessions().submit(player, "captcha-feedback", () ->
                        player.sendMessage(Component.text("인증을 먼저 완료해주세요.", NamedTextColor.RED)));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
        public void onPlayerCommand(PlayerCommandPreprocessEvent event) {

        if (isAnyCaptchaPending(event.getPlayer().getUniqueId())) {
            // 관리자 명령어 예외 처리
            if (event.getMessage().toLowerCase().startsWith("/l7def")) {
                return; // 허용
            }
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("캡챠 인증을 먼저 완료해주세요.", NamedTextColor.RED));
        }
    }
    @EventHandler
    public void onDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && isAnyCaptchaPending(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        tasks.cancel(event.getPlayer().getUniqueId());
        views.remove(event.getPlayer().getUniqueId());
    }

    @Override public void close() {
        closed = true;
        tasks.close();
        for (var entry : views.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.getOpenInventory().getTopInventory() == entry.getValue()) player.closeInventory();
        }
        views.clear();
    }
}
