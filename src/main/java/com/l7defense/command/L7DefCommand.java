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

        if (args[0].equalsIgnoreCase("reload")) {
            boolean reloaded = ((com.l7defense.L7DefensePlugin) plugin).reloadPlugin();
            sender.sendMessage(reloaded ? "§a[L7] 설정을 다시 불러왔습니다." : "§c[L7] 잘못된 설정입니다. 기존 방어를 유지합니다. 콘솔을 확인해주세요.");
            return true;
        }

        if (args[0].equalsIgnoreCase("xray")) {
            com.l7defense.L7DefensePlugin l7Plugin = (com.l7defense.L7DefensePlugin) plugin;
            com.l7defense.manager.PacketAntiXrayManager pxManager = l7Plugin.getPacketAntiXrayManager();
            if (args.length >= 2 && args[1].equalsIgnoreCase("engine2")) {
                if (pxManager != null) {
                    pxManager.setupPaperEngineMode2();
                    sender.sendMessage("§a[L7-AntiXray] Paper Engine-Mode 2(패킷 난수화) 설정을 적용하고 리로드했습니다.");
                } else {
                    sender.sendMessage("§c[L7-AntiXray] 패킷 매니저가 초기화되지 않았습니다.");
                }
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("decoy")) {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage("§c이 명령어는 인게임 플레이어만 실행할 수 있습니다.");
                    return true;
                }
                if (pxManager == null) {
                    sender.sendMessage("§c[L7-AntiXray] 패킷 매니저가 초기화되지 않았습니다.");
                    return true;
                }
                int dist = 3;
                if (args.length >= 3) {
                    try { dist = Math.max(1, Math.min(15, Integer.parseInt(args[2]))); } catch (NumberFormatException ignored) {}
                }
                int lifetime = pxManager.getDecoyLifetimeSeconds();
                if (args.length >= 4) {
                    try { lifetime = Math.max(5, Math.min(600, Integer.parseInt(args[3]))); } catch (NumberFormatException ignored) {}
                }
                org.bukkit.Location eye = player.getEyeLocation();
                org.bukkit.util.Vector dir = eye.getDirection().normalize();
                org.bukkit.Location target = eye.clone().add(dir.multiply(dist));
                org.bukkit.block.Block b = target.getBlock();
                org.bukkit.Material fakeMat = player.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER
                        ? org.bukkit.Material.ANCIENT_DEBRIS
                        : (target.getBlockY() < 0 ? org.bukkit.Material.DEEPSLATE_DIAMOND_ORE : org.bukkit.Material.DIAMOND_ORE);
                pxManager.injectDecoy(player, b.getLocation(), fakeMat, lifetime);
                player.sendMessage("§a[L7-AntiXray] 시선 방향 " + dist + "블록 앞 벽 속에 가짜 광석 허니팟을 주입했습니다! (유효시간: §e" + lifetime + "초§a)");
                player.sendMessage("§7위치: §e" + b.getWorld().getName() + " " + b.getX() + ", " + b.getY() + ", " + b.getZ() + " §7(가짜 광석: §b" + fakeMat.name() + "§7)");
                player.sendMessage("§7유효 시간이 지나면 자동으로 원래 암석으로 복구/삭제됩니다.");
                player.sendMessage("§e[테스트 가이드] §f엑스레이(ESP/텍스처팩)를 켜면 해당 벽 속 다이아몬드가 보입니다. 벽을 파고 들어가 채굴하면 즉시 적발 경고가 발동합니다!");
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
                if (pxManager != null && sender instanceof org.bukkit.entity.Player player) {
                    pxManager.clearDecoys(player);
                    sender.sendMessage("§a[L7-AntiXray] 활성화된 가짜 광석 허니팟을 모두 초기화했습니다.");
                } else {
                    sender.sendMessage("§c플레이어만 실행 가능합니다.");
                }
                return true;
            }
            sender.sendMessage("§b[L7 엑스레이 방어 및 패킷 난수화 상태]");
            boolean active = pxManager != null && pxManager.isPaperEngineMode2Active();
            sender.sendMessage("§7Paper Engine-Mode 2: " + (active ? "§a[가동 중]" : "§c[미적용/설정필요]"));
            sender.sendMessage("§7패킷 마스킹(은닉): " + (pxManager != null && pxManager.isClientMaskingEnabled() ? "§a[ON]" : "§c[OFF]"));
            sender.sendMessage("§7허니팟 패킷 트랩: " + (pxManager != null && pxManager.isDecoysEnabled() ? "§a[ON]" : "§c[OFF]"));
            if (sender instanceof org.bukkit.entity.Player player && pxManager != null) {
                sender.sendMessage("§7현재 활성 허니팟 수: §e" + pxManager.getActiveDecoyCount(player.getUniqueId()) + "개");
            }
            sender.sendMessage("§e/l7def xray engine2 §7- Paper Engine-Mode 2 즉시 강제 적용");
            sender.sendMessage("§e/l7def xray decoy [거리] §7- 현재 시선 앞 벽 속에 테스트용 가짜 다이아 허니팟 주입");
            sender.sendMessage("§e/l7def xray clear §7- 내 주변 가짜 허니팟 초기화");
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§b[L7 방어 시스템 상태]");
            sender.sendMessage("§7현재 차단된 캐시 IP 수: §f" + securityManager.getBlockedCount());
            sender.sendMessage("§7누적 차단 횟수: §f" + securityManager.getLifetimeBlockedCount());
            sender.sendMessage("§7공격 방어 모드: " + (securityManager.isUnderAttack() ? "§c[가동 중]" : "§a[안전함]"));
            return true;
        }

        if (args[0].equalsIgnoreCase("unban") || args[0].equalsIgnoreCase("unblock") || args[0].equalsIgnoreCase("reset")) {
            if (args.length < 2) {
                sender.sendMessage("§c사용법: /l7def unban <IP>");
                return true;
            }
            String targetIp = args[1];
            boolean wasBlocked = securityManager.unblockIp(targetIp);
            if (wasBlocked) {
                sender.sendMessage("§a[L7] 성공적으로 IP(" + targetIp + ")의 차단(블랙리스트)을 해제했습니다!");
            } else {
                sender.sendMessage("§e[L7] 해당 IP(" + targetIp + ")는 현재 차단 목록에 없지만, 관련된 모든 검증 캐시를 완전히 초기화했습니다.");
            }
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
                PenaltyAction action = PenaltyAction.valueOf(args[2].toUpperCase(java.util.Locale.ROOT));
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
            completions.add("gui");
            completions.add("modules");
            completions.add("toggle");
            completions.add("penalty");
            completions.add("xray");
            completions.add("unban");
            completions.add("test");
            completions.add("reload");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("xray")) {
            completions.add("engine2");
            completions.add("decoy");
            completions.add("clear");
            completions.add("status");
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
