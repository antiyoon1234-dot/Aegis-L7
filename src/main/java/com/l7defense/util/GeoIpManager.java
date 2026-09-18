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
    private final java.util.concurrent.Semaphore requests = new java.util.concurrent.Semaphore(4);
    private long requestWindow = System.nanoTime();
    private int requestCount;
    private volatile long cooldownUntil;
    private volatile boolean closed;
    private static final GeoData FAILURE = new GeoData(false, "XX", true);

    public GeoIpManager(Logger logger, boolean enabled, boolean blockVpn, boolean blockForeign, List<String> allowedCountries) {
        this.logger = logger;
        this.enabled = enabled;
        this.blockVpn = blockVpn;
        this.blockForeign = blockForeign;
        this.allowedCountries = List.copyOf(allowedCountries);

        this.geoCache = Caffeine.newBuilder()
                .expireAfter(new com.github.benmanes.caffeine.cache.Expiry<String, GeoData>() {
                    public long expireAfterCreate(String key, GeoData value, long time) { return value.apiFailed ? TimeUnit.SECONDS.toNanos(10) : TimeUnit.HOURS.toNanos(2); }
                    public long expireAfterUpdate(String key, GeoData value, long time, long duration) { return expireAfterCreate(key, value, time); }
                    public long expireAfterRead(String key, GeoData value, long time, long duration) { return duration; }
                })
                .maximumSize(20_000)
                .build();
    }

    /**
     * IP에 대한 보안 검증 결과를 반환합니다.
     * @return true: 차단 대상(VPN이거나 허용되지 않은 국가), false: 안전함
     */
    public boolean isRestricted(String ip, boolean checkVpn, boolean checkForeign) {
        if (closed || !enabled || (!checkVpn && !checkForeign) || !IpUtils.isLiteral(ip)) return false;

        GeoData data = geoCache.get(ip, this::fetchGeoData);
        if (data == null || data.apiFailed) return false; // API 실패 시 오탐 방지를 위해 통과

        if (checkVpn && blockVpn && data.isVpn) {
            logger.fine("[GeoIP] 데이터센터/프록시 차단: " + ip);
            return true;
        }

        if (checkForeign && blockForeign && !allowedCountries.isEmpty()) {
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

    private synchronized boolean reserveRequest() {
        long now = System.nanoTime();
        if (closed || now < cooldownUntil) return false;
        if (now - requestWindow >= TimeUnit.MINUTES.toNanos(1)) { requestWindow = now; requestCount = 0; }
        if (requestCount >= 45) return false;
        requestCount++;
        return true;
    }

    private GeoData fetchGeoData(String ip) {
        if (!requests.tryAcquire()) return FAILURE;
        HttpURLConnection conn = null;
        try {
            if (!reserveRequest()) return FAILURE;
            URL url = new URL(String.format(API_URL, ip));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "L7DefensePlugin/1.4");
            int status = conn.getResponseCode();
            if (status == 429) cooldownUntil = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
            if (status != 200) return FAILURE;
            try (InputStream in = conn.getInputStream()) {
                byte[] bytes = readResponse(in);
                if (bytes.length > 4096) return FAILURE;
                return parseResponse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            logger.fine("[GeoIP] API error: " + e.getMessage());
            logger.fine("[Anti-VPN] API error: " + e.getMessage());
            return FAILURE;
        } finally {
            if (conn != null) conn.disconnect();
            requests.release();
        }
    }

    static GeoData parseResponse(String response) {
        try {
            var json = com.google.gson.JsonParser.parseString(response).getAsJsonObject();
            if (!json.has("status") || !"success".equals(json.get("status").getAsString())) return FAILURE;
            String country = json.get("countryCode").getAsString();
            if (!country.matches("[A-Z]{2}") || !json.get("proxy").getAsJsonPrimitive().isBoolean()
                    || !json.get("hosting").getAsJsonPrimitive().isBoolean()) return FAILURE;
            return new GeoData(json.get("proxy").getAsBoolean() || json.get("hosting").getAsBoolean(), country, false);
        } catch (RuntimeException ex) { return FAILURE; }
    }

    private static byte[] readResponse(InputStream input) throws java.io.IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        var output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (output.size() <= 4096) {
            if (System.nanoTime() >= deadline) throw new java.net.SocketTimeoutException("GeoIP body deadline exceeded");
            int length = input.read(buffer, 0, Math.min(buffer.length, 4097 - output.size()));
            if (length < 0) break;
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }

    public void close() { closed = true; geoCache.invalidateAll(); }
}
