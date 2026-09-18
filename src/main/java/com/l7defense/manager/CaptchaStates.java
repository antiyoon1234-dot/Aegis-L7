package com.l7defense.manager;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.l7defense.module.DefenseModule;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** 접속 전에는 TTL/용량 제한, 접속 후에는 명시적 세션 종료로 인증 상태를 관리합니다. */
public final class CaptchaStates {
    private final Cache<UUID, DefenseModule> pending = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES).maximumSize(10_000).build();
    private final ConcurrentHashMap<UUID, DefenseModule> active = new ConcurrentHashMap<>();

    public void flag(UUID id, DefenseModule module) {
        if (active.containsKey(id)) active.putIfAbsent(id, module);
        else pending.asMap().putIfAbsent(id, module);
    }

    public void join(UUID id) {
        DefenseModule module = pending.asMap().remove(id);
        if (module != null) active.put(id, module);
    }

    public void start(UUID id, DefenseModule module) { active.putIfAbsent(id, module); }
    public DefenseModule get(UUID id) {
        DefenseModule module = active.get(id);
        return module != null ? module : pending.getIfPresent(id);
    }
    public boolean needs(UUID id, DefenseModule module) { return get(id) == module; }
    public boolean pass(UUID id, DefenseModule module) { return active.remove(id, module); }
    public void discardPending(UUID id) { pending.invalidate(id); }
    public void quit(UUID id) { active.remove(id); pending.invalidate(id); }
    public void clear() { active.clear(); pending.invalidateAll(); }
}
