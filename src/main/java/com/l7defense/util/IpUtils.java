package com.l7defense.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * IP 주소 정규화 유틸리티.
 * <p>
 * IPv6 주소의 다양한 표기법(축약, 전체 표기)을 통일된 형식으로 변환하여
 * 저장·조회·비교 시 불일치를 방지합니다.
 * <p>
 * 예시:
 * <ul>
 *   <li>{@code 2001:db8::1} → {@code 2001:db8:0:0:0:0:0:1}</li>
 *   <li>{@code ::ffff:192.168.1.1} → {@code 192.168.1.1} (IPv4-mapped)</li>
 *   <li>{@code 127.0.0.1} → {@code 127.0.0.1} (IPv4는 그대로)</li>
 * </ul>
 *
 * <p>{@link InetAddress#getByName(String)}은 IP 문자열 입력 시
 * DNS 조회 없이 로컬 파싱만 수행하므로 성능 영향이 없습니다.</p>
 */
public final class IpUtils {

    private static final Pattern IPV6_HEX_PATTERN = Pattern.compile("^[0-9a-fA-F:.]+$");

    private IpUtils() {
        // 유틸리티 클래스 — 인스턴스화 방지
    }

    /**
     * IP 주소 문자열을 정규화된 형식으로 변환합니다.
     * <p>
     * 파싱 실패 시 원본 문자열을 그대로 반환합니다.
     *
     * @param rawIp 원본 IP 주소 문자열
     * @return 정규화된 IP 주소 문자열
     */
    public static String normalize(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            return rawIp;
        }
        if (!isLiteral(rawIp)) return rawIp;
        try {
            return InetAddress.getByName(rawIp).getHostAddress();
        } catch (UnknownHostException e) {
            return rawIp;
        }
    }

    public static boolean isLiteral(String value) {
        if (value == null || value.isBlank()) return false;
        // 호스트 이름을 InetAddress에 넘기면 동기 DNS 조회가 발생합니다.
        if (value.indexOf(':') >= 0 && IPV6_HEX_PATTERN.matcher(value).matches()) {
            try { InetAddress.getByName(value); return true; }
            catch (UnknownHostException ex) { return false; }
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    /**
     * IP 주소 문자열 Set의 모든 요소를 정규화합니다.
     *
     * @param ips 원본 IP 주소 Set
     * @return 정규화된 IP 주소로 구성된 새 Set
     */
    public static Set<String> normalizeAll(Set<String> ips) {
        return ips.stream()
                .map(IpUtils::normalize)
                .collect(Collectors.toSet());
    }

    /** Player에서 null-safe IP 추출 */
    public static String getIp(org.bukkit.entity.Player player) {
        return player == null ? "unknown" : getIp(player.getAddress());
    }

    /** InetSocketAddress에서 null-safe IP 추출 */
    public static String getIp(java.net.InetSocketAddress address) {
        if (address == null) return "unknown";
        if (address.getAddress() != null) return normalize(address.getAddress().getHostAddress());
        String host = address.getHostString();
        return (host != null && !host.isBlank()) ? normalize(host) : "unknown";
    }
}
