package com.l7defense.command;

import com.l7defense.manager.AlertManager;
import com.l7defense.manager.SecurityManager;
import com.l7defense.module.DefenseModule;
import com.l7defense.module.ModuleConfigManager;
import com.l7defense.module.PenaltyAction;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

public class L7DefCommand implements CommandExecutor, TabCompleter {

    private final SecurityManager securityManager;
    private final AlertManager alertManager;
    private final com.l7defense.manager.AdminGuiManager guiManager;
    private final Plugin plugin;

    public L7DefCommand(SecurityManager securityManager, AlertManager alertManager, com.l7defense.manager.AdminGuiManager guiManager, Plugin plugin) {
        this.securityManager = securityManager;
        this.alertManager = alertManager;
        this.guiManager = guiManager;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("l7defense.admin")) {
            sender.sendMessage("권한이 없습니다.");
            return true;
        }

        if (args.length == 0) {
            if (sender instanceof org.bukkit.entity.Player player) {
                guiManager.openGui(player);
                return true;
            }
            sender.sendMessage("§c/l7def status §7- 방어 시스템 상태");
            sender.sendMessage("§c/l7def modules §7- 켜고 끌 수 있는 방어 모듈 목록");
            sender.sendMessage("§c/l7def toggle <모듈> §7- 해당 기능 ON/OFF");
            sender.sendMessage("§c/l7def penalty <모듈> <BAN|KICK|NOTIFY> §7- 처벌 수위 변경");
            return true;
        }

        if (args[0].equalsIgnoreCase("gui")) {
            if (sender instanceof org.bukkit.entity.Player player) {
                guiManager.openGui(player);
            } else {
                sender.sendMessage("§c콘솔에서는 GUI를 열 수 없습니다.");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§b[L7 방어 시스템 상태]");
            sender.sendMessage("§7현재 차단된 캐시 IP 수: §f" + securityManager.getBlockedCount());
            sender.sendMessage("§7누적 차단 횟수: §f" + securityManager.getLifetimeBlockedCount());
            sender.sendMessage("§7공격 방어 모드: " + (securityManager.isUnderAttack() ? "§c[가동 중]" : "§a[안전함]"));
            return true;
        }

        if (args[0].equalsIgnoreCase("modules")) {
            sender.sendMessage("§b[L7 방어 시스템 모듈 목록 (v1.6)]");
            ModuleConfigManager configManager = securityManager.getModuleManager();
            for (DefenseModule mod : DefenseModule.values()) {
                String status = configManager.isEnabled(mod) ? "§a[ON]" : "§c[OFF]";
                String pen = "§e" + configManager.getPenalty(mod).name();
                sender.sendMessage(status + " §f" + mod.getId() + " §7(" + pen + ") - " + mod.getDescription());
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("toggle")) {
            if (args.length < 2) {
                sender.sendMessage("§c사용법: /l7def toggle <모듈>");
                return true;
            }
            DefenseModule mod = DefenseModule.fromId(args[1]);
            if (mod == null) {
                sender.sendMessage("존재하지 않는 모듈입니다.");
                return true;
            }
            boolean newState = !securityManager.getModuleManager().isEnabled(mod);
            securityManager.getModuleManager().setEnabled(mod, newState);
            sender.sendMessage("§a[L7] §f" + mod.getId() + " §7모듈이 " + (newState ? "§a활성화" : "§c비활성화") + " §7되었습니다.");
            return true;
        }

        if (args[0].equalsIgnoreCase("test") && args.length == 2) {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage("인게임에서만 테스트 가능합니다.");
                return true;
            }
            DefenseModule mod = DefenseModule.fromId(args[1]);
            if (mod == null) {
                sender.sendMessage("존재하지 않는 모듈입니다.");
                return true;
            }
            sender.sendMessage("§e[L7] §f" + mod.getId() + " §e모듈의 공격자 시뮬레이션을 시작합니다...");
            securityManager.simulateTest(player, mod);
            return true;
        }

        if (args[0].equalsIgnoreCase("penalty")) {
            if (args.length < 3) {
                sender.sendMessage("§c사용법: /l7def penalty <모듈> <BAN|KICK|NOTIFY>");
                return true;
            }
            DefenseModule mod = DefenseModule.fromId(args[1]);
            if (mod == null) {
                sender.sendMessage("§c존재하지 않는 모듈입니다.");
                return true;
            }
            
            try {
                PenaltyAction action = PenaltyAction.valueOf(args[2].toUpperCase());
                securityManager.getModuleManager().setPenalty(mod, action);
                sender.sendMessage("§a모듈 '" + mod.getId() + "' 의 처벌 수위가 " + action.name() + "(으)로 변경되었습니다.");
            } catch (Exception e) {
                sender.sendMessage("§c잘못된 처벌 수위입니다. (BAN, KICK, NOTIFY 중 택 1)");
            }
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("l7defense.admin")) return completions;

        if (args.length == 1) {
            completions.add("status");
            completions.add("modules");
            completions.add("toggle");
            completions.add("penalty");
            completions.add("test");
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("toggle") || args[0].equalsIgnoreCase("penalty") || args[0].equalsIgnoreCase("test"))) {
            for (DefenseModule mod : DefenseModule.values()) {
                completions.add(mod.getId());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("penalty")) {
            completions.add("BAN");
            completions.add("KICK");
            completions.add("NOTIFY");
        }
        return completions;
    }
}
