package com.l7defense.module;

public enum DefenseModule {
    PING("ping", "Ping-before-Join 검사"),
    VPN("vpn", "GeoIP 및 VPN/데이터센터 검사"),
    ENTROPY("entropy", "무작위 및 알트 닉네임 감지"),
    GUI_CAPTCHA("gui_captcha", "인벤토리 GUI 캡챠"),
    ENTITY_CAPTCHA("entity_captcha", "3D 아머스탠드 캡챠"),
    RP_CAPTCHA("rp_captcha", "리소스팩 해시 캡챠"),
    MAP_CAPTCHA("map_captcha", "지도 OCR 캡챠"),
    PACKET_SPAM("spam", "포스트-로그인 인벤토리 패킷 스팸"),
    MOVEMENT("movement", "Baritone 등 기계적 시점 이동 감지"),
    BRAND("brand", "클라이언트 Brand 페이로드 타이밍"),
    EXPLOIT_CRASHER("exploit", "NBT 폭탄 및 Tab-Complete 크래셔 방어"),
    CHAT_AI("chat_ai", "유사도(머신러닝) 기반 챗봇 스팸 필터"),
    GEYSER_BLOCK("geyser_block", "Geyser/Floodgate(베드락) 스푸핑 차단"),
    SLP_CRASHER("slp_crasher", "핑 스크래퍼 역공격 (Scanner Crash)"),
    HONEYPOT("honeypot", "투명 NPC 허니팟 (킬아우라 감지)"),
    MACRO_CHECK("macro_check", "마우스 매크로 주파수 분석 (Auto-Clicker)"),
    MAP_STEALER("map_stealer", "월드 다운로더(WDL) 맵 유출 차단"),
    LIGHT_CRASHER("light_crasher", "조명 연산 랙(FastPlace/Nuker) 차단");

    private final String id;
    private final String description;

    DefenseModule(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String getId() { return id; }
    public String getDescription() { return description; }

    public static DefenseModule fromId(String id) {
        for (DefenseModule module : values()) {
            if (module.id.equalsIgnoreCase(id)) return module;
        }
        return null;
    }
}
