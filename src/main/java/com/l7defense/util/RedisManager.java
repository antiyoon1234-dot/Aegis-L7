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
    
    private JedisPool jedisPool;
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
            if (password != null && !password.isEmpty()) {
                this.jedisPool = new JedisPool(config, host, port, 2000, password);
            } else {
                this.jedisPool = new JedisPool(config, host, port, 2000);
            }
            logger.info("[Redis] 클러스터 연결 성공! 글로벌 동기화가 활성화되었습니다.");
            startSubscriber();
        } catch (Exception e) {
            logger.severe("[Redis] 연결 실패: " + e.getMessage());
            this.jedisPool = null;
        }
    }

    private void startSubscriber() {
        if (jedisPool == null) return;
        
        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                // 비동기로 수신되므로 메인 스레드나 캐시에 직접 반영
                if (CHANNEL_BLOCK.equals(channel)) {
                    securityManager.blockIp(message, "Redis 글로벌 차단");
                } else if (CHANNEL_UNBLOCK.equals(channel)) {
                    securityManager.unblockIp(message);
                }
            }
        };

        subThread = new Thread(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.subscribe(pubSub, CHANNEL_BLOCK, CHANNEL_UNBLOCK);
            } catch (Exception e) {
                if (enabled) logger.warning("[Redis] 구독 스레드 중단: " + e.getMessage());
            }
        }, "L7Defense-Redis-Sub");
        subThread.setDaemon(true);
        subThread.start();
    }

    /** 로컬에서 차단이 발생했을 때 Redis에 퍼블리시하여 다른 서버에 전파합니다. */
    public void publishBlock(String ip) {
        if (jedisPool == null) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish(CHANNEL_BLOCK, ip);
            } catch (Exception e) {
                logger.fine("[Redis] 퍼블리시 실패: " + e.getMessage());
            }
        });
    }

    public void publishUnblock(String ip) {
        if (jedisPool == null) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish(CHANNEL_UNBLOCK, ip);
            } catch (Exception e) {
                logger.fine("[Redis] 퍼블리시 실패: " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        if (pubSub != null && pubSub.isSubscribed()) {
            pubSub.unsubscribe();
        }
        if (subThread != null) {
            subThread.interrupt();
        }
        if (jedisPool != null) {
            jedisPool.close();
        }
    }
}
