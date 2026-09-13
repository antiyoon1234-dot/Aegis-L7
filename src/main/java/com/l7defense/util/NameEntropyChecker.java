package com.l7defense.util;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 섀넌 엔트로피(Shannon Entropy) 및 휴리스틱 분석을 통한 봇 닉네임 감지기.
 * <p>
 * 정규식으로는 막을 수 없는 완전 무작위 생성 문자열(예: qkwjdi29, xPzLmT)을
 * 문자의 무작위성(엔트로피)과 연속된 자음 갯수로 수학적으로 분석하여 적발합니다.
 */
public final class NameEntropyChecker {

    // 정규식(v1.3)과의 호환성을 위한 기본 패턴
    private static final Pattern BASIC_BOT_PATTERN = Pattern.compile(
            "^(?:Bot|Slave|Attack)\\d{2,}|^(?:Player|User|Test|Guest)\\d{5,}", Pattern.CASE_INSENSITIVE);

    /**
     * 닉네임이 알고리즘으로 자동 생성된 봇 패턴인지 검사합니다.
     */
    public static boolean isRandomBotName(String name) {
        if (name == null || name.length() < 3 || name.length() > 16) return true;
        
        // 1. 기본 정규식 검사
        if (BASIC_BOT_PATTERN.matcher(name).find()) return true;
        
        // 2. 알트 계정 제너레이터(Alt Generator) 사전 조합형 검사 (v1.6)
        // 예: AppleTree199, DarkKnight12 등 대문자 2개 + 끝 숫자 조합
        if (isAltGeneratorPattern(name)) return true;

        // 3. 섀넌 엔트로피 검사 (문자열의 복잡도/무작위성)
        // 닉네임은 보통 3.2를 넘지 않지만, 무작위 문자열은 3.5를 넘습니다.
        double entropy = calculateShannonEntropy(name);
        if (entropy > 3.4) return true;

        // 4. 자음 연속 규칙 (연속된 자음이 너무 많으면 키보드 난타/랜덤 문자열)
        if (getMaxConsecutiveConsonants(name) >= 5) return true;

        // 5. 문자와 숫자가 번갈아 나오는 횟수 (지나친 혼합 방지)
        if (getDigitLetterTransitions(name) >= 5) return true;

        return false;
    }

    private static boolean isAltGeneratorPattern(String name) {
        // [대문자로 시작하는 단어 2개] + [숫자 2~4개] 패턴 감지
        // 예: BlueSky99, RedDragon2023
        return Pattern.matches("^[A-Z][a-z]+[A-Z][a-z]+\\d{2,4}$", name);
    }

    private static double calculateShannonEntropy(String s) {
        Map<Character, Integer> charCounts = new HashMap<>();
        for (char c : s.toCharArray()) {
            charCounts.put(c, charCounts.getOrDefault(c, 0) + 1);
        }

        double entropy = 0.0;
        int len = s.length();
        for (int count : charCounts.values()) {
            double prob = (double) count / len;
            entropy -= prob * (Math.log(prob) / Math.log(2));
        }
        return entropy;
    }

    private static int getMaxConsecutiveConsonants(String s) {
        int max = 0;
        int current = 0;
        String lower = s.toLowerCase();
        for (char c : lower.toCharArray()) {
            if (Character.isLetter(c) && "aeiou".indexOf(c) == -1) {
                current++;
                if (current > max) max = current;
            } else {
                current = 0;
            }
        }
        return max;
    }

    private static int getDigitLetterTransitions(String s) {
        int transitions = 0;
        boolean wasDigit = Character.isDigit(s.charAt(0));
        for (int i = 1; i < s.length(); i++) {
            boolean isDigit = Character.isDigit(s.charAt(i));
            if (wasDigit != isDigit) {
                transitions++;
                wasDigit = isDigit;
            }
        }
        return transitions;
    }
}
