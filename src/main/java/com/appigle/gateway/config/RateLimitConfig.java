package com.appigle.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import reactor.core.publisher.Mono;

/**
 * Configuración para limitar el número de solicitudes por cliente.
 * 
 * Implementa estrategias para:
 * - Identificar clientes (por IP, ID de usuario, etc.)
 * - Limitar tasas de solicitudes por ventanas de tiempo
 * - Aplicar diferentes límites según el tipo de cliente
 * 
 * Actualmente usa un limitador en memoria para desarrollo,
 * pero está preparado para usar Redis en producción.
 */
@Configuration
@Profile("azure")
public class RateLimitConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(RateLimitConfig.class);

    /**
     * Resolutor de clave principal para identificar clientes.
     * Utiliza el ID de usuario cuando está disponible, de lo contrario usa la IP.
     * 
     * @return KeyResolver para identificar usuarios
     */
    @Bean
    @Primary
    public KeyResolver ipKeyResolver() {
        logger.info("Inicializando KeyResolver para rate limiting");
        
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just("user:" + userId);
            }
            
            return Mono.just("anonymous:" + 
                exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
        };
    }

    /**
     * Limitador de tasa para solicitudes.
     * En producción, se utilizaría Redis para state distribuido.
     * 
     * @return RedisRateLimiter configurado
     */
    @Bean
    @Primary
    public RedisRateLimiter memoryRateLimiter() {
        // Configuración actual permite 20 solicitudes por segundo con ráfaga de 40
        // Con límites más permisivos para facilitar desarrollo y pruebas
        logger.info("Configurando limitador de tasa en memoria: 20 tokens/seg, burst de 40");
        return new RedisRateLimiter(20, 40);
    }
    
    /**
     * Limitador más restrictivo para APIs sensibles.
     * 
     * @return RedisRateLimiter con límites más estrictos
     */
    @Bean
    public RedisRateLimiter restrictedRateLimiter() {
        // 5 solicitudes por segundo con ráfaga de 10
        logger.info("Configurando limitador de tasa restrictivo: 5 tokens/seg, burst de 10");
        return new RedisRateLimiter(5, 10);
    }
}