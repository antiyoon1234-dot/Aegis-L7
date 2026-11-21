package com.l7defense.util;

import java.util.Locale;

public final class Hostnames {
    private Hostnames() {}
    public static String normalize(String host) {
        if (host == null) return null;
        // 프록시 전달 메타데이터와 포트는 도메인 비교 대상이 아닙니다.
        host = host.split("\u0000", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end > 0) return host.substring(1, end);
        }
        int colon = host.indexOf(':');
        if (colon >= 0 && colon == host.lastIndexOf(':')) host = host.substring(0, colon);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host;
    }
    public static boolean matches(String host, String allowed) {
        String normalized = normalize(allowed);
        return host != null && normalized != null && !normalized.isEmpty()
                && (host.equals(normalized) || host.endsWith("." + normalized));
    }
}
