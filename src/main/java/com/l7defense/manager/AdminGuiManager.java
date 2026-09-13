package com.l7defense.manager;

import com.l7defense.module.DefenseModule;
import com.l7defense.module.ModuleConfigManager;
import com.l7defense.module.PenaltyAction;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class AdminGuiManager implements Listener {

    private final SecurityManager securityManager;
    private final String GUI_TITLE = "§8[L7] 방어 시스템 관리 패널";

    public AdminGuiManager(SecurityManager securityManager) {
        this.securityManager = securityManager;
    }

    public void openGui(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_TITLE);

        // 1. 서버 전체 통계 (0번 슬롯)
        ItemStack statsItem = new ItemStack(Material.BEACON);
        ItemMeta statsMeta = statsItem.getItemMeta();
        statsMeta.setDisplayName("§b§l[ 서버 보안 통계 ]");
        List<String> statsLore = new ArrayList<>();
        statsLore.add("§7누적 방어 횟수: §f" + securityManager.getLifetimeBlockedCount());
        statsLore.add("§7현재 차단 IP 수: §f" + securityManager.getBlockedCount());
        statsLore.add("§7공격 방어 모드: " + (securityManager.isUnderAttack() ? "§c§l[ 가동 중 ]" : "§a[ 안전함 ]"));
        statsMeta.setLore(statsLore);
        statsItem.setItemMeta(statsMeta);
        inv.setItem(4, statsItem);

        // 2. 모듈 리스트 렌더링 (18번 슬롯부터)
        int slot = 18;
        ModuleConfigManager configManager = securityManager.getModuleManager();
        for (DefenseModule module : DefenseModule.values()) {
            inv.setItem(slot++, createModuleItem(module, configManager));
        }

        player.openInventory(inv);
    }

    private ItemStack createModuleItem(DefenseModule module, ModuleConfigManager configManager) {
        Material mat = getMaterialForModule(module);
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        
        boolean isEnabled = configManager.isEnabled(module);
        PenaltyAction penalty = configManager.getPenalty(module);

        meta.setDisplayName((isEnabled ? "§a§l[ON] §f" : "§c§l[OFF] §7") + module.getId().toUpperCase());
        
        List<String> lore = new ArrayList<>();
        lore.add("§7" + module.getDescription());
        lore.add("");
        lore.add("§f▶ 현재 상태: " + (isEnabled ? "§a활성화됨" : "§c비활성화됨"));
        lore.add("§f▶ 적발 시 처벌: §e" + penalty.name());
        lore.add("");
        lore.add("§b[좌클릭] §7상태(ON/OFF) 전환");
        lore.add("§d[우클릭] §7처벌 수위 변경");
        
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private Material getMaterialForModule(DefenseModule module) {
        switch (module) {
            case PING: return Material.COMPASS;
            case VPN: return Material.ENDER_EYE;
            case ENTROPY: return Material.NAME_TAG;
            case GUI_CAPTCHA: return Material.CHEST;
            case ENTITY_CAPTCHA: return Material.ARMOR_STAND;
            case RP_CAPTCHA: return Material.JUKEBOX;
            case MAP_CAPTCHA: return Material.FILLED_MAP;
            case PACKET_SPAM: return Material.REPEATER;
            case MOVEMENT: return Material.LEATHER_BOOTS;
            case BRAND: return Material.MINECART;
            case EXPLOIT_CRASHER: return Material.TNT;
            case CHAT_AI: return Material.WRITABLE_BOOK;
            case GEYSER_BLOCK: return Material.BEDROCK;
            case SLP_CRASHER: return Material.TNT_MINECART;
            case HONEYPOT: return Material.GHAST_TEAR;
            case MACRO_CHECK: return Material.CLOCK;
            case MAP_STEALER: return Material.SPYGLASS;
            case LIGHT_CRASHER: return Material.GLOWSTONE;
            default: return Material.PAPER;
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(GUI_TITLE)) return;
        
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!player.hasPermission("l7defense.admin")) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR || !clicked.hasItemMeta()) return;

        String displayName = clicked.getItemMeta().getDisplayName();
        // 모듈 이름 파싱 ("§a§l[ON] §fPING" -> "PING")
        String moduleId = displayName.replaceAll("§[0-9a-fk-or]", "").replace("[ON] ", "").replace("[OFF] ", "").trim();

        DefenseModule module = DefenseModule.fromId(moduleId.toLowerCase());
        if (module == null) return;

        ModuleConfigManager configManager = securityManager.getModuleManager();

        // 좌클릭: ON/OFF 토글
        if (event.isLeftClick()) {
            boolean newState = !configManager.isEnabled(module);
            configManager.setEnabled(module, newState);
            player.sendMessage("§a[L7] §f" + module.getId() + " §7모듈이 " + (newState ? "§a활성화" : "§c비활성화") + " §7되었습니다.");
        } 
        // 우클릭: 처벌 상태 순환 변경 (BAN -> KICK -> NOTIFY -> BAN)
        else if (event.isRightClick()) {
            PenaltyAction current = configManager.getPenalty(module);
            PenaltyAction next = getNextPenalty(current);
            configManager.setPenalty(module, next);
            player.sendMessage("§a[L7] §f" + module.getId() + " §7모듈의 처벌이 §e" + next.name() + " §7(으)로 변경되었습니다.");
        }

        // GUI 리프레시
        openGui(player);
    }

    private PenaltyAction getNextPenalty(PenaltyAction current) {
        switch (current) {
            case BAN: return PenaltyAction.KICK;
            case KICK: return PenaltyAction.NOTIFY;
            case NOTIFY: return PenaltyAction.BAN;
            default: return PenaltyAction.BAN;
        }
    }
}
