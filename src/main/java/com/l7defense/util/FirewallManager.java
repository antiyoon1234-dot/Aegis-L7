package com.l7defense.util;

import org.bukkit.plugin.Plugin;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OS 레벨 방화벽 연동 매니저.
 * <p>
 * JVM 단에서 연결을 끊는 대신 iptables/ufw 등의 OS 명령어를 실행하여
 * Linux 커널 단위에서 패킷을 드롭시킵니다. CPU 부하를 극적으로 줄입니다.
 */
public final class FirewallManager implements AutoCloseable {
    private static final int MAX_RULES = 50_000;
    private record Commands(boolean enabled, String block, String unblock) {}
    private static final class Rule {
        final String ip;
        final Commands commands;
        volatile long expires;
        boolean applied;
        long retryAfter;
        Rule(String ip, Commands commands, long expires) { this.ip = ip; this.commands = commands; this.expires = expires; }
    }
    private final Logger logger;
    private volatile Commands commands;
    private final ConcurrentHashMap<String, Rule> rules = new ConcurrentHashMap<>();
    private final Thread worker;
    private final Object wakeup = new Object();
    private volatile boolean closed;
    private volatile long shutdownDeadline;
    private volatile Process running;
    private long lastCapacityWarning;

    public FirewallManager(Plugin plugin, Logger logger, boolean enabled, String blockCommand, String unblockCommand) {
        this.logger = logger;
        updateConfiguration(enabled, blockCommand, unblockCommand);
        worker = new Thread(this::run, "L7Defense-Firewall");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void updateConfiguration(boolean enabled, String block, String unblock) {
        commands = new Commands(enabled, block == null ? "" : block, unblock == null ? "" : unblock);
        if (!enabled) rules.values().forEach(rule -> rule.expires = 0);
    }

    /** IP를 방화벽 명령어를 통해 차단합니다. (비동기 실행) */
    public synchronized void executeBlock(String ip, long durationMillis) {
        Commands config = commands;
        if (closed || !config.enabled || config.block.isBlank() || !IpUtils.isLiteral(ip)) return;
        if (config.unblock.isBlank()) {
            warnCapacity("[방화벽] 해제 명령이 없어 OS 차단을 적용할 수 없습니다.");
            return;
        }
        Rule existing = rules.get(ip);
        if (existing != null) existing.expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis);
        else if (rules.size() < MAX_RULES) rules.put(ip, new Rule(ip, config, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis)));
        else warnCapacity("[방화벽] 대기/활성 규칙 한도 초과. 플러그인 IP 차단은 유지됩니다.");
        synchronized (wakeup) { wakeup.notifyAll(); }
    }

    private void warnCapacity(String message) {
        long now = System.nanoTime();
        if (lastCapacityWarning == 0 || now - lastCapacityWarning > TimeUnit.SECONDS.toNanos(30)) {
            lastCapacityWarning = now;
            logger.warning(message);
        }
    }

    /** IP 차단을 해제합니다. (비동기 실행) */
    public synchronized void executeUnblock(String ip) {
        Rule rule = rules.get(ip);
        if (rule != null) rule.expires = 0;
        synchronized (wakeup) { wakeup.notifyAll(); }
    }

    private void run() {
        while (!closed || (!rules.isEmpty() && System.nanoTime() < shutdownDeadline)) {
            for (Rule rule : rules.values()) {
                if (closed && System.nanoTime() >= shutdownDeadline) break;
                boolean desired = !closed && rule.expires > System.nanoTime();
                if (desired != rule.applied && System.nanoTime() >= rule.retryAfter) {
                    String command = (desired ? rule.commands.block : rule.commands.unblock).replace("{ip}", rule.ip);
                    if (execute(command)) rule.applied = desired;
                    else rule.retryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                }
                // 판정과 제거 사이에 들어온 새 차단 요청은 보존합니다.
                synchronized (this) {
                    if (!rule.applied && (closed || rule.expires <= System.nanoTime())) rules.remove(rule.ip, rule);
                }
            }
            try { synchronized (wakeup) { wakeup.wait(250L); } }
            catch (InterruptedException ignored) { /* 종료 정리 루프를 계속 실행 */ }
        }
        if (!rules.isEmpty()) logger.warning("[방화벽] 종료 중 해제하지 못한 규칙 " + rules.size() + "개: " + rules.keySet());
        rules.clear();
    }

    boolean execute(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder(splitCommand(command)).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            running = process;
            process.getOutputStream().close(); // sudo 등의 입력 대기를 EOF로 종료
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                logger.warning("[방화벽] 명령어 실행 시간 초과: " + command);
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) logger.warning("[방화벽] 명령어 실행 실패 (종료 코드: " + exitCode + "): " + command);
            else logger.fine("[방화벽] 명령어 실행 성공: " + command);
            return exitCode == 0;
        } catch (IOException | IllegalArgumentException e) {
            logger.log(Level.WARNING, "[방화벽] 실행 오류 (권한 문제일 수 있음): " + command, e);
            return false;
        } catch (InterruptedException e) {
            return false; // worker는 종료 요청 후 남은 규칙 해제를 수행합니다.
        } finally {
            if (process != null) {
                if (process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
                try { process.getInputStream().close(); } catch (IOException ignored) {}
                try { process.getErrorStream().close(); } catch (IOException ignored) {}
                try { process.getOutputStream().close(); } catch (IOException ignored) {}
            }
            running = null;
        }
    }

    public static List<String> splitCommand(String command) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        char quote = 0;
        for (char c : command.toCharArray()) {
            if ((c == '\'' || c == '"') && (quote == 0 || quote == c)) quote = quote == 0 ? c : 0;
            else if (Character.isWhitespace(c) && quote == 0) {
                if (!part.isEmpty()) { parts.add(part.toString()); part.setLength(0); }
            } else part.append(c);
        }
        if (quote != 0) throw new IllegalArgumentException("Unclosed command quote");
        if (!part.isEmpty()) parts.add(part.toString());
        if (parts.isEmpty()) throw new IllegalArgumentException("Empty command");
        return parts;
    }

    @Override public void close() {
        shutdownDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        closed = true;
        Process current = running;
        if (current != null && current.isAlive()) {
            current.descendants().forEach(ProcessHandle::destroyForcibly);
            current.destroyForcibly();
        }
        worker.interrupt();
        // 메인 스레드는 프로세스 종료나 OS 규칙 삭제를 기다리지 않습니다.
        synchronized (wakeup) { wakeup.notifyAll(); }
    }
}
