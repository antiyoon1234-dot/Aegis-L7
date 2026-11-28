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
    private final Map<DefenseModule, ModuleState> states = new java.util.concurrent.ConcurrentHashMap<>();

    public static class ModuleState {
        public final boolean enabled;
        public final PenaltyAction penalty;

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
        boolean dirty = false;
        for (DefenseModule module : DefenseModule.values()) {
            String path = "dynamic-modules." + module.getId();

            // config.yml에 없으면 기본값(NOTIFY 주의)으로 생성
            if (!config.contains(path)) {
                config.set(path + ".enabled", true);
                config.set(path + ".penalty", "NOTIFY");
                dirty = true;
            }

            boolean enabled = config.getBoolean(path + ".enabled", true);
            String penaltyStr = config.getString(path + ".penalty", "NOTIFY").toUpperCase(java.util.Locale.ROOT);

            PenaltyAction action;
            try {
                action = PenaltyAction.valueOf(penaltyStr);
            } catch (Exception e) {
                action = PenaltyAction.NOTIFY;
            }

            states.put(module, new ModuleState(enabled, action));
        }
        if (dirty) {
            plugin.saveConfig();
        }
    }

    public boolean isEnabled(DefenseModule module) {
        if (module == null) return false;
        ModuleState state = states.get(module);
        return state != null && state.enabled;
    }

    public PenaltyAction getPenalty(DefenseModule module) {
        if (module == null) return PenaltyAction.NOTIFY;
        ModuleState state = states.get(module);
        return state != null ? state.penalty : PenaltyAction.NOTIFY;
    }

    public void setEnabled(DefenseModule module, boolean enabled) {
        if (module == null) return;
        states.compute(module, (key, old) -> new ModuleState(enabled, old != null ? old.penalty : PenaltyAction.NOTIFY));
        plugin.getConfig().set("dynamic-modules." + module.getId() + ".enabled", enabled);
        plugin.saveConfig();
    }

    public void setPenalty(DefenseModule module, PenaltyAction penalty) {
        if (module == null || penalty == null) return;
        states.compute(module, (key, old) -> new ModuleState(old != null ? old.enabled : true, penalty));
        plugin.getConfig().set("dynamic-modules." + module.getId() + ".penalty", penalty.name());
        plugin.saveConfig();
    }
}
