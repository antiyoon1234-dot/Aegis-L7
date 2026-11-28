package com.l7defense.util;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Paper 서버의 패킷 단위 광석 난수화(Engine-Mode 2) 설정을 자동으로 감지하고 적용하는 설정 유틸리티입니다.
 * 
 * Paper의 Engine-Mode 2는 청크 패킷 전송 시 벽에 가려진 블록을 가짜 광석으로 난수화하여
 * 클라이언트의 엑스레이 텍스처팩 및 ESP 모드를 시각적으로 무력화합니다.
 */
public class PaperAntiXrayConfigurator {

    public static final List<String> DEFAULT_HIDDEN_BLOCKS = Arrays.asList(
            "air",
            "copper_ore",
            "deepslate_copper_ore",
            "raw_copper_block",
            "diamond_ore",
            "deepslate_diamond_ore",
            "gold_ore",
            "deepslate_gold_ore",
            "iron_ore",
            "deepslate_iron_ore",
            "raw_iron_block",
            "lapis_ore",
            "deepslate_lapis_ore",
            "redstone_ore",
            "deepslate_redstone_ore",
            "emerald_ore",
            "deepslate_emerald_ore",
            "coal_ore",
            "deepslate_coal_ore",
            "ancient_debris"
    );

    public static final List<String> DEFAULT_REPLACEMENT_BLOCKS = Arrays.asList(
            "stone",
            "oak_planks",
            "deepslate",
            "netherrack",
            "tuff",
            "andesite",
            "diorite",
            "granite"
    );

    /**
     * Paper 서버 설정 파일 경로를 탐색합니다.
     * Paper 1.19+ 에서는 config/paper-world-defaults.yml 을 사용합니다.
     */
    public static File getPaperConfigFile(File serverRootDir) {
        File configDir = new File(serverRootDir, "config");
        File modernFile = new File(configDir, "paper-world-defaults.yml");
        if (modernFile.exists()) return modernFile;

        File rootWorldDefaults = new File(serverRootDir, "paper-world-defaults.yml");
        if (rootWorldDefaults.exists()) return rootWorldDefaults;

        File legacyFile = new File(serverRootDir, "paper.yml");
        if (legacyFile.exists()) return legacyFile;

        // 존재하지 않는 경우 최신 Paper 표준 경로로 지정
        return modernFile;
    }

    /**
     * 현재 Paper 서버의 Engine-Mode 2 활성화 여부를 확인합니다.
     */
    public static boolean isEngineMode2Enabled(File serverRootDir) {
        File file = getPaperConfigFile(serverRootDir);
        if (!file.exists()) return false;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        if (config.contains("anticheat.anti-xray")) {
            return config.getBoolean("anticheat.anti-xray.enabled", false)
                    && config.getInt("anticheat.anti-xray.engine-mode", 1) == 2;
        }
        if (config.contains("world-settings.default.anti-xray")) {
            return config.getBoolean("world-settings.default.anti-xray.enabled", false)
                    && config.getInt("world-settings.default.anti-xray.engine-mode", 1) == 2;
        }
        return false;
    }

    /**
     * Paper 설정 파일에 Engine-Mode 2(패킷 난수화) 및 광석 은닉 설정을 자동 적용합니다.
     * 이미 Engine-Mode 2가 활성화된 경우 파일을 불필요하게 덮어쓰지 않습니다.
     */
    public static boolean applyEngineMode2(File serverRootDir, Logger logger) {
        return applyEngineMode2(serverRootDir, logger, false);
    }

    public static boolean applyEngineMode2(File serverRootDir, Logger logger, boolean forceReload) {
        try {
            if (!forceReload && isEngineMode2Enabled(serverRootDir)) {
                if (logger != null) {
                    logger.info("[L7-AntiXray] Paper 패킷 난수화(Engine-Mode 2)가 이미 활성화되어 있습니다.");
                }
                return true;
            }

            File file = getPaperConfigFile(serverRootDir);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            YamlConfiguration config = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();

            boolean isLegacy = file.getName().equals("paper.yml");
            String prefix = isLegacy ? "world-settings.default.anti-xray" : "anticheat.anti-xray";

            config.set(prefix + ".enabled", true);
            config.set(prefix + ".engine-mode", 2);
            config.set(prefix + ".chunk-edge-mode", 2);
            config.set(prefix + ".max-block-height", 64);
            config.set(prefix + ".update-radius", 2);
            config.set(prefix + ".use-permission", false);

            if (!config.contains(prefix + ".hidden-blocks")) {
                config.set(prefix + ".hidden-blocks", DEFAULT_HIDDEN_BLOCKS);
            }
            if (!config.contains(prefix + ".replacement-blocks")) {
                config.set(prefix + ".replacement-blocks", DEFAULT_REPLACEMENT_BLOCKS);
            }

            config.save(file);
            if (logger != null) {
                logger.info("[L7-AntiXray] Paper 패킷 난수화(Engine-Mode 2) 설정이 " + file.getPath() + " 에 적용되었습니다. (완전 적용을 위해 서버 재시작을 권장합니다)");
            }

            // 개별 월드 폴더 내 paper-world.yml 이 있는 경우 함께 갱신
            updateWorldFolders(serverRootDir);

            // 부팅 중 강제 paper reload는 타 플러그인/월드 충돌을 유발할 수 있으므로 명시적 명령 시에만 실행
            if (forceReload) {
                tryReloadPaper();
            }
            return true;
        } catch (IOException e) {
            if (logger != null) {
                logger.warning("[L7-AntiXray] Paper 설정 저장 실패: " + e.getMessage());
            }
            return false;
        }
    }

    private static void updateWorldFolders(File serverRootDir) {
        File[] files = serverRootDir.listFiles();
        if (files == null) return;
        for (File dir : files) {
            if (dir.isDirectory()) {
                File worldPaper = new File(dir, "paper-world.yml");
                if (worldPaper.exists()) {
                    try {
                        YamlConfiguration wConfig = YamlConfiguration.loadConfiguration(worldPaper);
                        String prefix = wConfig.contains("anticheat.anti-xray") ? "anticheat.anti-xray" : "anti-xray";
                        if (wConfig.contains(prefix)) {
                            wConfig.set(prefix + ".enabled", true);
                            wConfig.set(prefix + ".engine-mode", 2);
                            wConfig.save(worldPaper);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private static void tryReloadPaper() {
        try {
            if (Bukkit.getServer() != null && Bukkit.getConsoleSender() != null) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "paper reload");
            }
        } catch (Throwable ignored) {
            // 테스트 환경이거나 Paper 리로드 명령 미지원 환경 시 안전하게 무시
        }
    }
}
