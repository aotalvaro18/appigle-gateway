package com.appigle.gateway.config;

import com.appigle.gateway.filter.headers.CustomForwardedHeadersFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;

@Configuration
@Profile("azure")
public class MinimalGatewayConfig {

    @Bean
    public RouteLocator minimalGatewayRoutes(  
            RouteLocatorBuilder builder,
            KeyResolver ipKeyResolver,
            RedisRateLimiter memoryRateLimiter) {
       
        CustomForwardedHeadersFilter customFilter = new CustomForwardedHeadersFilter();
        
        return builder.routes()
            .route("auth-service", r -> r
                .path("/api/auth/**", "/api/users/**", "/api/email-verification/**")
                .filters(f -> f
                    .filter(customFilter)
                    .addRequestHeader("X-Gateway-Source", "appigle-gateway")
                    .circuitBreaker(config -> config
                        .setName("authServiceCircuitBreaker")
                        .setFallbackUri("forward:/fallback/auth"))
                    .requestRateLimiter(config -> config
                        .setRateLimiter(memoryRateLimiter)
                        .setKeyResolver(ipKeyResolver)))
                .uri("http://auth-service.internal.politedune-48459ced.eastus.azurecontainerapps.io:8080"))
            .route("fallback", r -> r
                .path("/fallback/**")
                .uri("forward:/"))
            .build();
    }
}