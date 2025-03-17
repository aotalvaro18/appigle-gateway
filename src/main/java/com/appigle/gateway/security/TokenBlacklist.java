package com.appigle.gateway.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
//import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestiona una lista negra de tokens JWT revocados.
 */
@Component

public class TokenBlacklist {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final String keyPrefix;
    private final Duration timeToLive;
    private final boolean enabled;
    private final boolean redisBasedBlacklist;
    
    private final Map<String, Long> inMemoryBlacklist = new ConcurrentHashMap<>();

    @Autowired
    public TokenBlacklist(
            @Value("${security.jwt.token-blacklist.time-to-live:86400}") long timeToLiveSeconds,
            @Value("${security.jwt.token-blacklist.enabled:true}") boolean enabled,
            @Value("${rate-limiting.redis-based:false}") boolean redisBasedBlacklist) {
        
        this(null, timeToLiveSeconds, enabled, redisBasedBlacklist);
    }
    
    // Constructor alternativo cuando Redis está habilitado
    public TokenBlacklist(
            ReactiveRedisTemplate<String, String> redisTemplate,
            @Value("${security.jwt.token-blacklist.time-to-live:86400}") long timeToLiveSeconds,
            @Value("${security.jwt.token-blacklist.enabled:true}") boolean enabled,
            @Value("${rate-limiting.redis-based:false}") boolean redisBasedBlacklist) {
        
        this.redisTemplate = redisTemplate;
        this.keyPrefix = "token:blacklist:";
        this.timeToLive = Duration.ofSeconds(timeToLiveSeconds);
        this.enabled = enabled;
        this.redisBasedBlacklist = redisBasedBlacklist;
        
        // Iniciar limpieza periódica para implementación en memoria
        startCleanupTask();
    }

    /**
     * Verifica si un token está en la lista negra.
     */
    public Mono<Boolean> isBlacklisted(String token) {
        if (!enabled) {
            return Mono.just(false);
        }
        
        if (!redisBasedBlacklist || redisTemplate == null) {
            // Implementación en memoria
            return Mono.just(inMemoryBlacklist.containsKey(token));
        }
        
        // Implementación con Redis
        return redisTemplate.hasKey(keyPrefix + token);
    }

    /**
     * Añade un token a la lista negra.
     */
    public Mono<Boolean> blacklist(String token) {
        if (!enabled) {
            return Mono.just(true);
        }
        
        if (!redisBasedBlacklist || redisTemplate == null) {
            // Implementación en memoria
            inMemoryBlacklist.put(token, System.currentTimeMillis() + timeToLive.toMillis());
            return Mono.just(true);
        }
        
        // Implementación con Redis
        return redisTemplate.opsForValue()
                .set(keyPrefix + token, "1", timeToLive);
    }

    /**
     * Añade un token a la lista negra con un tiempo de vida personalizado.
     */
    public Mono<Boolean> blacklist(String token, Duration customTimeToLive) {
        if (!enabled) {
            return Mono.just(true);
        }
        
        if (!redisBasedBlacklist || redisTemplate == null) {
            // Implementación en memoria
            inMemoryBlacklist.put(token, System.currentTimeMillis() + customTimeToLive.toMillis());
            return Mono.just(true);
        }
        
        // Implementación con Redis
        return redisTemplate.opsForValue()
                .set(keyPrefix + token, "1", customTimeToLive);
    }
    
    /**
     * Inicia una tarea periódica para limpiar tokens expirados.
     */
    private void startCleanupTask() {
        if (!redisBasedBlacklist || redisTemplate == null) {
            Thread cleanupThread = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(300000); // 5 minutos
                        long now = System.currentTimeMillis();
                        inMemoryBlacklist.entrySet().removeIf(entry -> entry.getValue() < now);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
            
            cleanupThread.setDaemon(true);
            cleanupThread.setName("token-blacklist-cleanup");
            cleanupThread.start();
        }
    }
}