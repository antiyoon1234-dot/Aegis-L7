package com.l7defense.module;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.Map;

/**
 * 인게임 명령어로 모듈을 켜고 끄거나 페널티를 변경할 수 있도록
 * 상태를 메모리에 보관하고 config.yml에 즉각 반영하는 매니저입니다.
 */
public class ModuleConfigManager {

    private final Plugin plugin;
    private final Map<DefenseModule, ModuleState> states = new EnumMap<>(DefenseModule.class);

    public static class ModuleState {
        public boolean enabled;
        public PenaltyAction penalty;

        public ModuleState(boolean enabled, PenaltyAction penalty) {
            this.enabled = enabled;
            this.penalty = penalty;
        }
    }

    public ModuleConfigManager(Plugin plugin) {
        this.plugin = plugin;
        loadFromConfig();
    }

    public void loadFromConfig() {
        FileConfiguration config = plugin.getConfig();
        for (DefenseModule module : DefenseModule.values()) {
            String path = "dynamic-modules." + module.getId();
            
            // config.yml에 없으면 기본값으로 생성
            if (!config.contains(path)) {
                config.set(path + ".enabled", true);
                config.set(path + ".penalty", "BAN");
            }

            boolean enabled = config.getBoolean(path + ".enabled", true);
            String penaltyStr = config.getString(path + ".penalty", "BAN").toUpperCase();
            
            PenaltyAction action;
            try {
                action = PenaltyAction.valueOf(penaltyStr);
            } catch (Exception e) {
                action = PenaltyAction.BAN;
            }
            
            states.put(module, new ModuleState(enabled, action));
        }
        plugin.saveConfig();
    }

    public boolean isEnabled(DefenseModule module) {
        return states.get(module).enabled;
    }

    public PenaltyAction getPenalty(DefenseModule module) {
        return states.get(module).penalty;
    }

    public void setEnabled(DefenseModule module, boolean enabled) {
        states.get(module).enabled = enabled;
        plugin.getConfig().set("dynamic-modules." + module.getId() + ".enabled", enabled);
        plugin.saveConfig();
    }

    public void setPenalty(DefenseModule module, PenaltyAction penalty) {
        states.get(module).penalty = penalty;
        plugin.getConfig().set("dynamic-modules." + module.getId() + ".penalty", penalty.name());
        plugin.saveConfig();
    }
}
