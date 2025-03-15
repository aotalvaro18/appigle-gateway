package com.appigle.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Gestiona una lista negra de tokens JWT revocados mediante Redis.
 * Los tokens añadidos a esta lista son considerados inválidos incluso si aún no han expirado.
 */
@Component
public class TokenBlacklist {

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final String keyPrefix;
    private final Duration timeToLive;
    private final boolean enabled;

    public TokenBlacklist(
            ReactiveRedisTemplate<String, String> redisTemplate,
            @Value("${security.jwt.token-blacklist.time-to-live:86400}") long timeToLiveSeconds,
            @Value("${security.jwt.token-blacklist.enabled:true}") boolean enabled) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = "token:blacklist:";
        this.timeToLive = Duration.ofSeconds(timeToLiveSeconds);
        this.enabled = enabled;
    }

    /**
     * Verifica si un token está en la lista negra.
     * 
     * @param token Token JWT a verificar
     * @return true si el token está en la lista negra, false en caso contrario
     */
    public Mono<Boolean> isBlacklisted(String token) {
        if (!enabled) {
            return Mono.just(false);
        }
        return redisTemplate.hasKey(keyPrefix + token);
    }

    /**
     * Añade un token a la lista negra.
     * 
     * @param token Token JWT a revocar
     * @return true si el token fue añadido correctamente
     */
    public Mono<Boolean> blacklist(String token) {
        if (!enabled) {
            return Mono.just(true);
        }
        return redisTemplate.opsForValue()
                .set(keyPrefix + token, "1", timeToLive);
    }

    /**
     * Añade un token a la lista negra con un tiempo de vida personalizado.
     * 
     * @param token Token JWT a revocar
     * @param customTimeToLive Tiempo personalizado que el token estará en la lista negra
     * @return true si el token fue añadido correctamente
     */
    public Mono<Boolean> blacklist(String token, Duration customTimeToLive) {
        if (!enabled) {
            return Mono.just(true);
        }
        return redisTemplate.opsForValue()
                .set(keyPrefix + token, "1", customTimeToLive);
    }
}