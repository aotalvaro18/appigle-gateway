package com.appigle.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
//import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;

@Configuration
@Profile("azure-recovery")
public class MinimalGatewayConfig {
    
    @Bean
    // Eliminar la anotación @Primary
    public RouteLocator minimalGatewayRoutes(  // Cambiar el nombre del método
            RouteLocatorBuilder builder,
            KeyResolver ipKeyResolver,
            RedisRateLimiter memoryRateLimiter) {
        
        return builder.routes()
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
                .uri("http://auth-service"))
            .route("fallback", r -> r
                .path("/fallback/**")
                .uri("forward:/"))
            .build();
    }
}