package com.l7defense.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * GeoIP 및 데이터센터 검사 통합 매니저 (기존 VpnChecker 확장).
 * <p>
 * 국가 코드를 확인하여 허용되지 않은 국가의 IP를 공격 시 완벽히 락다운(차단)합니다.
 */
public final class GeoIpManager {

    private final Logger logger;
    private final boolean enabled;
    private final boolean blockVpn;
    private final boolean blockForeign;
    private final List<String> allowedCountries;

    private static final String API_URL = "http://ip-api.com/json/%s?fields=status,countryCode,proxy,hosting";

    public record GeoData(boolean isVpn, String countryCode, boolean apiFailed) {}

    private final Cache<String, GeoData> geoCache;

    public GeoIpManager(Logger logger, boolean enabled, boolean blockVpn, boolean blockForeign, List<String> allowedCountries) {
        this.logger = logger;
        this.enabled = enabled;
        this.blockVpn = blockVpn;
        this.blockForeign = blockForeign;
        this.allowedCountries = allowedCountries;
        
        this.geoCache = Caffeine.newBuilder()
                .expireAfterWrite(2, TimeUnit.HOURS)
                .maximumSize(20_000)
                .build();
    }

    /**
     * IP에 대한 보안 검증 결과를 반환합니다.
     * @return true: 차단 대상(VPN이거나 허용되지 않은 국가), false: 안전함
     */
    public boolean isRestricted(String ip) {
        if (!enabled) return false;

        GeoData data = geoCache.get(ip, this::fetchGeoData);
        if (data == null || data.apiFailed) return false; // API 실패 시 오탐 방지를 위해 통과

        if (blockVpn && data.isVpn) {
            logger.fine("[GeoIP] 데이터센터/프록시 차단: " + ip);
            return true;
        }

        if (blockForeign && !allowedCountries.isEmpty()) {
            boolean countryAllowed = false;
            for (String allowed : allowedCountries) {
                if (allowed.equalsIgnoreCase(data.countryCode)) {
                    countryAllowed = true;
                    break;
                }
            }
            if (!countryAllowed) {
                logger.fine("[GeoIP] 해외 IP 차단 (" + data.countryCode + "): " + ip);
                return true;
            }
        }
        return false;
    }

    private GeoData fetchGeoData(String ip) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(String.format(API_URL, ip));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setRequestProperty("User-Agent", "L7DefensePlugin/1.4");
            
            if (conn.getResponseCode() != 200) {
                return new GeoData(false, "XX", true);
            }
            
            try (java.io.InputStream in = conn.getInputStream();
                 java.util.Scanner scanner = new java.util.Scanner(in)) {
                String response = scanner.useDelimiter("\\A").next();
                
                boolean isProxy = response.contains("\"proxy\":true");
                boolean isHosting = response.contains("\"hosting\":true");
                
                String country = "XX";
                int idx = response.indexOf("\"countryCode\":\"");
                if (idx != -1 && idx + 17 <= response.length()) {
                    country = response.substring(idx + 15, idx + 17);
                }
                
                return new GeoData(isProxy || isHosting, country, false);
            }
        } catch (Exception e) {
            return new GeoData(false, "XX", true);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
