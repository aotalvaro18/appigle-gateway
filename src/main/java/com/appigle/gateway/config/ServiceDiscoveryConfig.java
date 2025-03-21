package com.appigle.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
//simport org.springframework.web.server.ServerWebExchange;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;


/**
 * Configuración para el descubrimiento de servicios y enrutamiento en Azure Container Apps.
 * 
 * Esta clase proporciona beans y configuraciones necesarias para:
 * 1. Enrutamiento basado en el DNS interno de Azure Container Apps
 * 2. WebClient para comunicación entre servicios
 * 3. Configuración específica para Azure
 */
@Configuration
public class ServiceDiscoveryConfig {
    private static final Logger logger = LoggerFactory.getLogger(ServiceDiscoveryConfig.class);

    @Value("${spring.application.name:appigle-gateway}")
    private String applicationName;

    @Value("${service-discovery.route-id-prefix:}")
    private String routeIdPrefix;
    
    @Value("${service-discovery.lower-case-service-id:true}")
    private boolean lowerCaseServiceId;
    
    @Value("${CONTAINER_APP_ENV_DNS_SUFFIX:internal.azurecontainerapps.io}")
    private String containerAppDnsSuffix;
    
    private final Environment environment;

    public ServiceDiscoveryConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * Configura las rutas para microservicios en Azure Container Apps.
     * Este bean se activa con el perfil "azure" para usar el DNS interno
     * de Azure Container Apps en lugar de service discovery tradicional.
     * 
     * @param builder el RouteLocatorBuilder para construir las rutas
     * @return el RouteLocator con las rutas configuradas para Azure
     */

    @Bean
    @Profile("azure")
    public RouteLocator azureServiceRoutes(RouteLocatorBuilder builder) {
    logger.info("Configurando rutas para Azure Container Apps con sufijo DNS: {}", containerAppDnsSuffix);
    
    return builder.routes()
        // Ruta específica para solicitudes OPTIONS (debe ir primero)
        .route("cors-preflight", r -> r
            .method(HttpMethod.OPTIONS)  // Solo para solicitudes OPTIONS
            .filters(f -> f
                .filter((exchange, chain) -> {
                    logger.info("Manejando solicitud OPTIONS directamente en el Gateway");
                    ServerHttpResponse response = exchange.getResponse();
                    HttpHeaders headers = response.getHeaders();
                    
                    String origin = exchange.getRequest().getHeaders().getOrigin();
                    if (origin != null && (
                            origin.equals("https://thankful-meadow-07b64540f.6.azurestaticapps.net") ||
                            origin.equals("https://app.appigle.com") ||
                            origin.equals("https://admin.appigle.com"))) {
                        headers.add("Access-Control-Allow-Origin", origin);
                        headers.add("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS,PATCH");
                        headers.add("Access-Control-Allow-Headers", "Origin,Content-Type,Accept,Authorization,X-Requested-With,X-API-Key");
                        headers.add("Access-Control-Allow-Credentials", "true");
                        headers.add("Access-Control-Max-Age", "3600");
                    }
                    
                    response.setStatusCode(HttpStatus.OK);
                    return Mono.empty();
                }))
            .uri("no://op"))
            
        // Resto de las rutas normales...
        .route("auth-service", r -> r
            .path("/api/auth/**", "/api/users/**", "/api/email-verification/**", "/api/mfa/**")
            .filters(f -> f
                .addRequestHeader("X-Forwarded-Service", "auth-service")
                .circuitBreaker(config -> config
                    .setName("authServiceCircuitBreaker")
                    .setFallbackUri("forward:/fallback/auth")))
            .uri("https://auth-service.internal." + containerAppDnsSuffix))

            // Aquí puedes añadir más rutas para otros servicios
            // .route("content-service", r -> r
            //     .path("/api/content/**")
            //     .filters(f -> f
            //         .addRequestHeader("X-Forwarded-Service", "content-service")
            //         .circuitBreaker(config -> config
            //             .setName("contentServiceCircuitBreaker")
            //             .setFallbackUri("forward:/fallback/content")))
            //     .uri("https://content-service.internal." + containerAppDnsSuffix))
        
        .build();
    }

    /**
     * Configura Web Client para comunicación entre servicios en Azure.
     * 
     * Este cliente se utiliza para comunicación entre servicios en Azure,
     * sin necesidad de balanceo de carga ya que Azure Container Apps
     * gestiona esto internamente.
     *
     * @return WebClient.Builder configurado para Azure
     */
    @Bean
    @Profile("azure")
    public WebClient.Builder azureWebClientBuilder() {
        logger.info("Configurando WebClient para Azure Container Apps");
        
        return WebClient.builder()
            .defaultHeader("X-Gateway-Source", applicationName)
            .defaultHeader("X-Azure-Environment", "true");
    }
    
    /**
     * Configura Web Client estándar (no específico de Azure).
     * Este bean se usará cuando no esté activo el perfil "azure".
     *
     * @return WebClient.Builder estándar
     */
    @Bean
    @Primary
    @Profile("!azure")
    public WebClient.Builder standardWebClientBuilder() {
        return WebClient.builder()
            .defaultHeader("X-Gateway-Source", applicationName);
    }
    
    /**
     * Proporciona información sobre la configuración actual del service discovery.
     * Útil para diagnóstico y logging.
     * 
     * @return Información de configuración formateada
     */
    public String getDiscoveryInfo() {
        StringBuilder info = new StringBuilder();
        info.append("Configuración de Service Discovery:\n");
        info.append("- Perfil activo: ").append(String.join(", ", environment.getActiveProfiles())).append("\n");
        
        if (environment.matchesProfiles("azure")) {
            info.append("- Modo: Azure Container Apps\n");
            info.append("- DNS Suffix: ").append(containerAppDnsSuffix).append("\n");
        } else {
            info.append("- Estrategia de balanceo: ")
                .append(environment.matchesProfiles("random-lb") ? "Aleatoria" : "Round Robin").append("\n");
            info.append("- Discovery locator habilitado: ")
                .append(environment.getProperty("spring.cloud.gateway.discovery.locator.enabled", "true")).append("\n");
        }
        
        info.append("- Prefijo de ruta: ").append(routeIdPrefix).append("\n");
        info.append("- Service ID en minúsculas: ").append(lowerCaseServiceId);
        
        return info.toString();
    }
}