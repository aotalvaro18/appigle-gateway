package com.appigle.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
// Comentamos/Eliminamos la anotación de perfil para que se aplique siempre
// import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Configuración CORS (Cross-Origin Resource Sharing) para API Gateway.
 *
 * Permite gestionar solicitudes desde diferentes orígenes de manera segura,
 * controlando qué dominios pueden interactuar con la API, qué métodos pueden
 * utilizar y qué encabezados pueden incluir.
 */
@Configuration
// Eliminamos @Profile("azure") para que se aplique en todos los entornos
public class CorsConfig {
   
    private static final Logger logger = LoggerFactory.getLogger(CorsConfig.class);
   
    @Value("${security.cors.allowed-origins:https://app.appigle.com,https://admin.appigle.com,https://thankful-meadow-07b64540f.6.azurestaticapps.net}")
    private List<String> allowedOrigins;
   
    @Value("${security.cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS,PATCH}")
    private List<String> allowedMethods;
   
    @Value("${security.cors.allowed-headers:Origin,Content-Type,Accept,Authorization,X-Requested-With,X-API-Key,Access-Control-Request-Method,Access-Control-Request-Headers}")
    private List<String> allowedHeaders;
   
    @Value("${security.cors.exposed-headers:Content-Disposition,X-Auth-Token,X-Request-ID}")
    private List<String> exposedHeaders;
   
    @Value("${security.cors.max-age:3600}")
    private Long maxAge;
   
    @Value("${security.cors.allow-credentials:true}")
    private Boolean allowCredentials;
    
    /**
     * Filtro de alta prioridad para manejar específicamente las solicitudes OPTIONS (preflight)
     * Se ejecuta antes de cualquier otro filtro de seguridad para evitar bloqueos
     * 
     * @return WebFilter configurado para solicitudes OPTIONS
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public WebFilter corsPreFlightFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            // Procesar solo solicitudes OPTIONS
            if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
                String origin = exchange.getRequest().getHeaders().getOrigin();
                
                logger.debug("Procesando solicitud preflight CORS desde origen: {}", origin);
                
                // Verificar si el origen está permitido
                if (origin != null && allowedOrigins.contains(origin)) {
                    logger.debug("Origen válido para CORS: {}", origin);
                    
                    // Agregar headers CORS para respuesta preflight
                    exchange.getResponse().getHeaders().add("Access-Control-Allow-Origin", origin);
                    exchange.getResponse().getHeaders().add("Access-Control-Allow-Methods", String.join(",", allowedMethods));
                    exchange.getResponse().getHeaders().add("Access-Control-Allow-Headers", String.join(",", allowedHeaders));
                    exchange.getResponse().getHeaders().add("Access-Control-Expose-Headers", String.join(",", exposedHeaders));
                    exchange.getResponse().getHeaders().add("Access-Control-Max-Age", maxAge.toString());
                    
                    if (allowCredentials) {
                        exchange.getResponse().getHeaders().add("Access-Control-Allow-Credentials", "true");
                    }
                    
                    // Responder con OK para permitir que continúe la solicitud real
                    exchange.getResponse().setStatusCode(HttpStatus.OK);
                    return Mono.empty();
                }
            }
            
            return chain.filter(exchange);
        };
    }
   
    /**
     * Configura el filtro CORS centralizado.
     *
     * @return CorsWebFilter configurado
     */
    @Bean
    public CorsWebFilter corsWebFilter() {
        logger.info("Inicializando configuración CORS con orígenes: {}", allowedOrigins);
       
        // Configuración CORS
        CorsConfiguration corsConfig = new CorsConfiguration();
        
        // Configuramos explícitamente cada origen permitido
        // Importante: No usar setAllowedOrigins cuando allowCredentials es true
        for (String origin : allowedOrigins) {
            corsConfig.addAllowedOrigin(origin);
        }
        
        // Configuramos los métodos permitidos
        corsConfig.setAllowedMethods(allowedMethods);
        corsConfig.setAllowedHeaders(allowedHeaders);
        corsConfig.setExposedHeaders(exposedHeaders);
        corsConfig.setMaxAge(maxAge);
        corsConfig.setAllowCredentials(allowCredentials);
       
        // Registro de la configuración CORS para todas las rutas
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);
       
        // APIs públicas - más permisivas
        CorsConfiguration publicConfig = new CorsConfiguration(corsConfig);
        publicConfig.addAllowedMethod("GET");  // Solo permitir GET en endpoints públicos
        source.registerCorsConfiguration("/api/public/**", publicConfig);
       
        // APIs administrativas - más restrictivas
        CorsConfiguration adminConfig = new CorsConfiguration(corsConfig);
        adminConfig.setAllowedOrigins(
            allowedOrigins.stream()
                .filter(origin -> origin.contains("admin"))
                .toList()
        );
        source.registerCorsConfiguration("/api/admin/**", adminConfig);
       
        // Rutas de autenticación - necesitan misma configuración permisiva que la general
        source.registerCorsConfiguration("/api/auth/**", corsConfig);
       
        // Documentación de API
        source.registerCorsConfiguration("/v3/api-docs/**", corsConfig);
        source.registerCorsConfiguration("/swagger-ui/**", corsConfig);
       
        logger.debug("Configuración CORS completada: {}", getConfigurationInfo());
        return new CorsWebFilter(source);
    }
   
    /**
     * Genera información detallada sobre la configuración CORS actual.
     *
     * @return String con detalles de configuración CORS
     */
    public String getConfigurationInfo() {
        StringBuilder info = new StringBuilder();
        info.append("Configuración CORS:\n");
        info.append("- Orígenes permitidos: ").append(allowedOrigins).append("\n");
        info.append("- Métodos permitidos: ").append(allowedMethods).append("\n");
        info.append("- Headers permitidos: ").append(allowedHeaders).append("\n");
        info.append("- Headers expuestos: ").append(exposedHeaders).append("\n");
        info.append("- Tiempo máximo de caché: ").append(maxAge).append(" segundos\n");
        info.append("- Permite credenciales: ").append(allowCredentials);
       
        return info.toString();
    }
}