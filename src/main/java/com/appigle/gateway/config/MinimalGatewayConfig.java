package com.appigle.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;

/**
 * Configuración minimalista del Gateway para Azure.
 * Define las rutas y filtros para los microservicios.
 */
@Configuration
@Profile("azure")
public class MinimalGatewayConfig {

    private static final Logger logger = LoggerFactory.getLogger(MinimalGatewayConfig.class);

    /**
     * Configura las rutas para los microservicios en Azure.
     * 
     * @param builder Constructor de rutas
     * @param ipKeyResolver Resolvedor de claves para rate limiting
     * @param memoryRateLimiter Limitador de tasa
     * @return RouteLocator configurado
     */
    @Bean
    public RouteLocator minimalGatewayRoutes(  
            RouteLocatorBuilder builder,
            KeyResolver ipKeyResolver,
            RedisRateLimiter memoryRateLimiter) {
       
        logger.info("Configurando rutas para API Gateway en Azure");
        
        return builder.routes()
            // Ruta específica para operaciones POST en /api/auth/register
            .route("auth-register-post", r -> r
                .path("/api/auth/register")
                .and()
                .method(HttpMethod.POST)
                .filters(f -> f
                    .addRequestHeader("X-Gateway-Source", "appigle-gateway")
                    .circuitBreaker(config -> config
                        .setName("authServiceCircuitBreaker")
                        .setFallbackUri("forward:/fallback/auth"))
                    .requestRateLimiter(config -> config
                        .setRateLimiter(memoryRateLimiter)
                        .setKeyResolver(ipKeyResolver)))
                .uri("http://auth-service.internal.politedune-48459ced.eastus.azurecontainerapps.io:8080"))
            
            // Ruta específica para operaciones POST en /api/auth/login
            .route("auth-login-post", r -> r
                .path("/api/auth/login")
                .and()
                .method(HttpMethod.POST)
                .filters(f -> f
                    .addRequestHeader("X-Gateway-Source", "appigle-gateway")
                    .circuitBreaker(config -> config
                        .setName("authServiceCircuitBreaker")
                        .setFallbackUri("forward:/fallback/auth"))
                    .requestRateLimiter(config -> config
                        .setRateLimiter(memoryRateLimiter)
                        .setKeyResolver(ipKeyResolver)))
                .uri("http://auth-service.internal.politedune-48459ced.eastus.azurecontainerapps.io:8080"))
                
            // Ruta general para el servicio de autenticación
            .route("auth-service", r -> r
                .path("/api/auth/**", "/api/users/**", "/api/email-verification/**")
                .filters(f -> f
                    .addRequestHeader("X-Gateway-Source", "appigle-gateway")
                    .circuitBreaker(config -> config
                        .setName("authServiceCircuitBreaker")
                        .setFallbackUri("forward:/fallback/auth"))
                    .requestRateLimiter(config -> config
                        .setRateLimiter(memoryRateLimiter)
                        .setKeyResolver(ipKeyResolver)))
                .uri("http://auth-service.internal.politedune-48459ced.eastus.azurecontainerapps.io:8080"))
                
            // Ruta para fallbacks
            .route("fallback", r -> r
                .path("/fallback/**")
                .uri("forward:/"))
                
            // Rutas para controladores de prueba
            .route("test-controllers", r -> r
                .path("/test/**")
                .uri("forward:/"))
                
            .build();
    }
}