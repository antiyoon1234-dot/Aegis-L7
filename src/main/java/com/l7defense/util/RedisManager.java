package com.l7defense.util;

import com.l7defense.manager.SecurityManager;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.logging.Logger;

/**
 * BungeeCord/Velocity 등 멀티 서버 환경을 위한 Redis 글로벌 밴리스트 동기화.
 * <p>
 * 한 서버에서 차단된 IP가 실시간으로 다른 서버에도 차단되도록 Pub/Sub을 사용합니다.
 */
public final class RedisManager {

    private final Plugin plugin;
    private final Logger logger;
    private final SecurityManager securityManager;
    private final boolean enabled;

    private volatile JedisPool jedisPool;
    private volatile Jedis subscriberConnection;
    private volatile boolean closed;
    private final java.util.concurrent.ThreadPoolExecutor publisher = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1024),
            task -> { Thread t = new Thread(task, "L7Defense-Redis-Publish"); t.setDaemon(true); return t; },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private JedisPubSub pubSub;
    private Thread subThread;

    private static final String CHANNEL_BLOCK = "l7def:block";
    private static final String CHANNEL_UNBLOCK = "l7def:unblock";

    public RedisManager(Plugin plugin, Logger logger, SecurityManager securityManager,
                        boolean enabled, String host, int port, String password) {
        this.plugin = plugin;
        this.logger = logger;
        this.securityManager = securityManager;
        this.enabled = enabled;

        if (!enabled) return;

        try {
            JedisPoolConfig config = new JedisPoolConfig();
            config.setMaxTotal(8);
            config.setMaxWait(java.time.Duration.ofMillis(500));
            if (password != null && !password.isEmpty()) {
                this.jedisPool = new JedisPool(config, host, port, 2000, password);
            } else {
                this.jedisPool = new JedisPool(config, host, port, 2000);
            }
            startSubscriber();
        } catch (Exception e) {
            logger.severe("[Redis] 연결 실패: " + e.getMessage());
            if (this.jedisPool != null) this.jedisPool.close();
            this.jedisPool = null;
        }
    }

    private void startSubscriber() {
        if (jedisPool == null) return;

        pubSub = new JedisPubSub() {
            @Override
            public void onSubscribe(String channel, int count) {
                if (closed) unsubscribe(); // 종료 직후 자동 재연결된 구독도 즉시 해제
                else if (count == 2) logger.info("[Redis] 클러스터 연결 성공! 글로벌 동기화가 활성화되었습니다.");
            }
            @Override
            public void onMessage(String channel, String message) {
                if (closed || !IpUtils.isLiteral(message)) return;
                // 비동기로 수신되므로 메인 스레드나 캐시에 직접 반영
                if (CHANNEL_BLOCK.equals(channel)) {
                    securityManager.receiveRemoteBlock(message);
                } else if (CHANNEL_UNBLOCK.equals(channel)) {
                    securityManager.receiveRemoteUnblock(message);
                }
            }
        };

        JedisPool pool = jedisPool;
        subThread = new Thread(() -> {
            while (!closed) {
                try (Jedis jedis = pool.getResource()) {
                    subscriberConnection = jedis;
                    if (closed) break;
                    jedis.subscribe(pubSub, CHANNEL_BLOCK, CHANNEL_UNBLOCK);
                } catch (Exception e) {
                    if (!closed) logger.warning("[Redis] 구독 스레드 중단: " + e.getMessage());
                } finally { subscriberConnection = null; }
                if (!closed) {
                    try { Thread.sleep(2000L); }
                    catch (InterruptedException ex) { if (closed) break; }
                }
            }
        }, "L7Defense-Redis-Sub");
        subThread.setDaemon(true);
        subThread.start();
    }

    /** 로컬에서 차단이 발생했을 때 Redis에 퍼블리시하여 다른 서버에 전파합니다. */
    public void publishBlock(String ip) { publish(CHANNEL_BLOCK, ip); }

    public void publishUnblock(String ip) { publish(CHANNEL_UNBLOCK, ip); }

    private void publish(String channel, String ip) {
        JedisPool pool = jedisPool;
        if (closed || pool == null || !IpUtils.isLiteral(ip)) return;
        try {
            publisher.execute(() -> {
                if (closed) return;
                try (Jedis jedis = pool.getResource()) { jedis.publish(channel, ip); }
                catch (Exception e) { logger.fine("[Redis] 퍼블리시 실패: " + e.getMessage()); }
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            logger.fine("[Redis] 퍼블리시 대기열 한도 초과 또는 종료 중");
        }
    }

    public void shutdown() {
        closed = true;
        publisher.shutdownNow();
        Jedis subscriber = subscriberConnection;
        if (subscriber != null) {
            try { subscriber.getClient().disconnect(); }
            catch (RuntimeException ex) { logger.fine("[Redis] 구독 종료: " + ex.getMessage()); }
        }
        if (subThread != null) subThread.interrupt();
        JedisPool pool = jedisPool;
        if (pool != null) pool.close();
    }
}
