package com.l7defense.util;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;
import java.util.logging.Logger;

/**
 * 외부 API(ip-api.com)를 활용하여 VPN/Proxy/데이터센터 IP를 식별합니다.
 * <p>
 * 비동기 이벤트(AsyncPlayerPreLoginEvent) 내에서만 호출되어야 하며,
 * 블로킹 방지를 위해 짧은 타임아웃을 적용합니다.
 */
public final class VpnChecker {

    private final Logger logger;
    private final boolean enabled;
    
    // 무료 플랜은 초당 45회 요청 제한이 있으므로 무분별한 호출 방지 필수
    private static final String API_URL = "http://ip-api.com/json/%s?fields=proxy,hosting";

    public VpnChecker(Logger logger, boolean enabled) {
        this.logger = logger;
        this.enabled = enabled;
    }

    /**
     * IP가 VPN, 프록시 또는 데이터센터(호스팅)인지 확인합니다.
     * HTTP 요청 실패 또는 타임아웃 시 안전을 위해 false를 반환합니다.
     *
     * @param ip 검사할 IP 주소
     * @return VPN/Hosting 여부 (true = 차단 대상)
     */
    public boolean isVpnOrHosting(String ip) {
        if (!enabled) return false;

        HttpURLConnection conn = null;
        try {
            URL url = new URL(String.format(API_URL, ip));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(800); // 0.8초 타임아웃
            conn.setReadTimeout(800);
            conn.setRequestProperty("User-Agent", "L7DefensePlugin/1.3");

            int status = conn.getResponseCode();
            if (status != 200) {
                return false;
            }

            try (InputStream in = conn.getInputStream();
                 Scanner scanner = new Scanner(in)) {
                String response = scanner.useDelimiter("\\A").next();
                
                // 간단한 JSON 파싱 ("proxy":true 또는 "hosting":true)
                boolean isProxy = response.contains("\"proxy\":true");
                boolean isHosting = response.contains("\"hosting\":true");

                if (isProxy || isHosting) {
                    logger.fine("[Anti-VPN] 데이터센터/프록시 IP 감지: " + ip);
                    return true;
                }
            }
        } catch (Exception e) {
            // 타임아웃 등 예외 발생 시 무시하고 통과 (오탐 방지)
            logger.fine("[Anti-VPN] API 호출 실패 (" + ip + "): " + e.getMessage());
        }
        return false;
    }
}
