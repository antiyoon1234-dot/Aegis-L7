package com.l7defense.module;

public enum PenaltyAction {
    BAN,    // IP 블랙리스트 등재 및 강퇴 + OP 알림
    KICK,   // 1회성 강퇴 (블랙리스트 X) + OP 알림
    NOTIFY  // 강퇴하지 않고 통과시키되 OP에게 경고 알림만 전송
}
