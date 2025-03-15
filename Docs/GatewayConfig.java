package com.appigle.gateway.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.codec.CodecCustomizer;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.RequestSizeGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;

import com.appigle.gateway.filter.MetricsFilter;
import reactor.core.publisher.Mono;
//este es un comentario para nueva imagen temporal y luego quitar
/**
 * Configuración centralizada para el API Gateway de AppIgle.
 * 
 * Esta clase define las rutas, filtros, limitadores de tasa y políticas
 * de resiliencia para todos los servicios de la plataforma.
 */
@Configuration
public class GatewayConfig {

    // Obtenemos el sufijo DNS para Azure Container Apps
    @Value("${CONTAINER_APP_ENV_DNS_SUFFIX:internal.azurecontainerapps.io}")
    private String containerAppDnsSuffix;

    //-------------------------------------------------------------------------
    // Key Resolvers para Rate Limiting
    //-------------------------------------------------------------------------
    
    /**
     * Resolutor de clave principal para rate limiting basado en ID de usuario o IP.
     * 
     * Utiliza el ID de usuario cuando está disponible en el header X-User-Id,
     * de lo contrario usa la dirección IP como identificador.
     * 
     * @return KeyResolver para identificar usuarios
     */
    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
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
     * Resolutor de clave alternativo basado únicamente en IP.
     * 
     * Útil para limitar todas las solicitudes de una misma IP
     * independientemente del usuario.
     * 
     * @return KeyResolver basado en IP
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
                exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
        );
    }
    
    //-------------------------------------------------------------------------
    // Rate Limiters
    //-------------------------------------------------------------------------
    
    /**
     * Limitador de tasa para el servicio de autenticación.
     * 
     * Permite más solicitudes que el limitador por defecto debido
     * a la naturaleza crítica de este servicio.
     * 
     * @return RedisRateLimiter configurado para auth_service
     */
    @Bean
    @ConditionalOnProperty(name = "spring.redis.host") //quitar en PRODUCCION AZURE
    public RedisRateLimiter authServiceRateLimiter() {
        // 20 tokens por segundo, burst de 40
        return new RedisRateLimiter(20, 40);
    }
    
    /**
     * Limitador de tasa por defecto para servicios generales.
     * 
     * @return RedisRateLimiter con configuración estándar
     */
    @Bean
    @ConditionalOnProperty(name = "spring.redis.host") //quitar en PRODUCCION AZURE
    @Primary  // Para inyectar un RateLimiter en requestRateLimiterGatewayFilterFactory
    public RedisRateLimiter defaultRateLimiter() {
        // 10 tokens por segundo, burst de 20
        return new RedisRateLimiter(10, 20);
    }
    
    /**
     * Limitador de tasa para API públicas.
     * 
     * Más restrictivo para proteger endpoints públicos.
     * 
     * @return RedisRateLimiter para APIs públicas
     */
    @Bean
    @ConditionalOnProperty(name = "spring.redis.host") //quitar en PRODUCCION AZURE
    public RedisRateLimiter publicApiRateLimiter() {
        // 5 tokens por segundo, burst de 10
        return new RedisRateLimiter(5, 10);
    }
    
    //-------------------------------------------------------------------------
    // Filtros para tamaño de solicitud
    //-------------------------------------------------------------------------
    
    /**
     * Factory para crear filtros de límite de tamaño de solicitud.
     * 
     * @return RequestSizeGatewayFilterFactory
     */
    @Bean("myRequestSizeFilterFactory")
    public RequestSizeGatewayFilterFactory requestSizeFilterFactory() {
        return new RequestSizeGatewayFilterFactory();
    }
    
    /**
     * Filtro preconfigurado para limitar solicitudes a 5MB.
     * 
     * @param factory Factory para crear el filtro
     * @return GatewayFilter configurado
     */
    @Bean
    public GatewayFilter maxSize5MbFilter(
            @Qualifier("myRequestSizeFilterFactory") RequestSizeGatewayFilterFactory factory) {
        return factory.apply(config -> config.setMaxSize(DataSize.ofMegabytes(5)));
    }
    
    /**
     * Filtro preconfigurado para limitar solicitudes a 1MB.
     * Útil para endpoints que no necesitan procesar archivos grandes.
     * 
     * @param factory Factory para crear el filtro
     * @return GatewayFilter configurado
     */
    @Bean
    public GatewayFilter maxSize1MbFilter(
            @Qualifier("myRequestSizeFilterFactory") RequestSizeGatewayFilterFactory factory) {
        return factory.apply(config -> config.setMaxSize(DataSize.ofMegabytes(1)));
    }

    /**
     * Configuración global del tamaño máximo de request/response en memoria.
     * 
     * @return CodecCustomizer para configurar límites de memoria
     */
    @Bean
    public CodecCustomizer maxInMemorySizeCodecCustomizer() {
        return codecs -> codecs.defaultCodecs().maxInMemorySize((int) DataSize.ofMegabytes(10).toBytes());
    }
    
    /**
     * Filtro global para configurar timeouts de respuesta específicos por ruta.
     * Los timeouts base se configuran en application.yml pero este filtro
     * permite aplicar valores específicos según la ruta solicitada.
     *
     * @return GlobalFilter que configura los timeouts por ruta
     */
    @Bean
    public GlobalFilter responseTimeoutFilter() {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getURI().getPath();
            Duration timeout;

            // Configurar timeouts específicos por ruta
            if (path.startsWith("/api/auth/") || path.startsWith("/api/users/") || 
                path.startsWith("/api/email-verification/")) {
                timeout = Duration.ofSeconds(5); // 5 segundos para servicios de autenticación
            } else if (path.startsWith("/api/content/") || path.startsWith("/api/media/")) {
                timeout = Duration.ofSeconds(10); // 10 segundos para contenido y media
            } else if (path.startsWith("/api/public/")) {
                timeout = Duration.ofSeconds(3); // 3 segundos para APIs públicas
            } else if (path.startsWith("/api/events/")) {
                timeout = Duration.ofSeconds(5); // 5 segundos para eventos
            } else {
                timeout = Duration.ofSeconds(5); // Default timeout
            }

            // Aplicar timeout al intercambio actual
            exchange.getAttributes().put("response-timeout", timeout.toMillis());
            
            return chain.filter(exchange);
        };
    }
    
    //-------------------------------------------------------------------------
    // Configuración de retries
    //-------------------------------------------------------------------------
    
    /**
     * Configuración para reintentos de solicitudes fallidas.
     * 
     * @return RetryGatewayFilterFactory.RetryConfig configurado
     */
    private RetryGatewayFilterFactory.RetryConfig standardRetryConfig() {
        RetryGatewayFilterFactory.RetryConfig config = new RetryGatewayFilterFactory.RetryConfig();
        config.setRetries(3);
        config.setMethods(HttpMethod.GET, HttpMethod.PUT);
        config.setStatuses(HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE);
        config.setBackoff(Duration.ofMillis(50), Duration.ofMillis(500), 2, true);
        return config;
    }
     
    // ELIMINADO: El método corsFilter() ha sido eliminado para evitar conflictos con CorsConfig
    
    //-------------------------------------------------------------------------
    // Configuración de Rutas
    //-------------------------------------------------------------------------
    
    /**
     * Define todas las rutas y sus configuraciones asociadas.
     * Este método se activa cuando NO estamos en el perfil "azure".
     * 
     * @param builder Constructor de rutas
     * @param userKeyResolver Resolutor de claves para rate limiting
     * @param authServiceRateLimiter Limitador para servicios de autenticación
     * @param defaultRateLimiter Limitador estándar
     * @param publicApiRateLimiter Limitador para APIs públicas
     * @param maxSize5MbFilter Filtro de tamaño 5MB
     * @param maxSize1MbFilter Filtro de tamaño 1MB
     * @return RouteLocator configurado
     */
        
    /**
     * Define rutas específicas para Azure Container Apps.
     * Este método solo se activa cuando estamos en el perfil "azure".
     * 
     * Utiliza el DNS interno de Azure en lugar de "lb://" para el enrutamiento.
     * 
     * @param builder Constructor de rutas
     * @param userKeyResolver Resolutor de claves para rate limiting
     * @param ipKeyResolver Resolutor de claves basado en IP
     * @param authServiceRateLimiter Limitador para servicios de autenticación
     * @param defaultRateLimiter Limitador estándar
     * @param publicApiRateLimiter Limitador para APIs públicas
     * @param maxSize5MbFilter Filtro de tamaño 5MB
     * @param maxSize1MbFilter Filtro de tamaño 1MB
     * @return RouteLocator configurado para Azure
     */
    @Bean
    @Profile("azure")
    public RouteLocator azureRouteLocator(
            RouteLocatorBuilder builder, 
            KeyResolver userKeyResolver,
            KeyResolver ipKeyResolver,
            RedisRateLimiter authServiceRateLimiter,
            RedisRateLimiter defaultRateLimiter,
            RedisRateLimiter publicApiRateLimiter,
            GatewayFilter maxSize5MbFilter,
            GatewayFilter maxSize1MbFilter) {
            
        return builder.routes()
            // Rutas para el servicio de autenticación
            .route("auth_service", r -> r
                .path("/api/auth/**", "/api/users/**", "/api/email-verification/**", 
                      "/api/mfa/**", "/api/permissions/**", "/api/roles/**", 
                      "/api/registration/**", "/api/invitations/**")
                .filters(f -> f
                    .filter(maxSize5MbFilter)  // Limita tamaño de solicitud
                    .retry(3)  // Reintenta 3 veces en caso de error
                    .circuitBreaker(config -> config
                        .setName("authServiceCircuitBreaker")
                        .setFallbackUri("forward:/fallback/auth"))
                    .requestRateLimiter(config -> config
                        .setRateLimiter(authServiceRateLimiter)
                        .setKeyResolver(userKeyResolver))
                    .addResponseHeader("X-App-Version", "v1.0.0")
                    .retry(retryConfig -> retryConfig
                        .setRetries(3)
                        .setMethods(HttpMethod.GET, HttpMethod.PUT)
                        .setStatuses(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.BAD_GATEWAY)
                        .setBackoff(Duration.ofMillis(50), Duration.ofMillis(500), 2, true)))
                .uri("https://auth-service.internal." + containerAppDnsSuffix))
                
            // Rutas para el servicio de contenido
            .route("content_service", r -> r
                .path("/api/content/**", "/api/media/**", "/api/uploads/**")
                .filters(f -> f
                    .filter(maxSize5MbFilter)
                    .circuitBreaker(config -> config
                        .setName("contentServiceCircuitBreaker")
                        .setFallbackUri("forward:/fallback/content"))
                    .requestRateLimiter(config -> config
                        .setRateLimiter(defaultRateLimiter)
                        .setKeyResolver(userKeyResolver))
                    .retry(retryConfig -> retryConfig
                        .setRetries(2)
                        .setMethods(HttpMethod.GET)
                        .setStatuses(HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE)))
                .uri("https://content-service.internal." + containerAppDnsSuffix))
                
            // Rutas para APIs públicas
            .route("public_api", r -> r
                .path("/api/public/**")
                .filters(f -> f
                    .filter(maxSize1MbFilter)
                    .circuitBreaker(config -> config
                        .setName("publicApiCircuitBreaker")
                        .setFallbackUri("forward:/fallback/public"))
                    .requestRateLimiter(config -> config
                        .setRateLimiter(publicApiRateLimiter)
                        .setKeyResolver(ipKeyResolver))  // Usa IP para limitar APIs públicas
                    .addResponseHeader("Cache-Control", "public, max-age=300"))
                .uri("https://public-api-service.internal." + containerAppDnsSuffix))
                
            // Rutas para servicios de eventos
            .route("events_service", r -> r
                .path("/api/events/**", "/api/notifications/**")
                .filters(f -> f
                    .filter(maxSize1MbFilter)
                    .circuitBreaker(config -> config
                        .setName("eventsServiceCircuitBreaker")
                        .setFallbackUri("forward:/fallback/events"))
                    .requestRateLimiter(config -> config
                        .setRateLimiter(defaultRateLimiter)
                        .setKeyResolver(userKeyResolver)))
                .uri("https://events-service.internal." + containerAppDnsSuffix))
                
            // Documentación de OpenAPI/Swagger accesible a través del gateway
            .route("api_docs", r -> r
                .path("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .filters(f -> f
                    .addResponseHeader("X-Content-Type-Options", "nosniff")
                    .requestRateLimiter(config -> config
                        .setRateLimiter(publicApiRateLimiter)
                        .setKeyResolver(ipKeyResolver)))
                .uri("https://api-docs.internal." + containerAppDnsSuffix))
                
            .build();
    }
    
    /**
     * Filtro global para métricas y monitoreo de solicitudes.
     * 
     * @param metricsFilter Filtro de métricas inyectado
     * @return GlobalFilter para ser aplicado a todas las solicitudes
     */
    
}