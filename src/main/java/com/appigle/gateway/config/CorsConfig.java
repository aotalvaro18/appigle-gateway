package com.appigle.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Configuración CORS (Cross-Origin Resource Sharing) para API Gateway.
 *
 * Permite gestionar solicitudes desde diferentes orígenes de manera segura,
 * controlando qué dominios pueden interactuar con la API, qué métodos pueden
 * utilizar y qué encabezados pueden incluir.
 */
@Configuration
@Profile("azure")
public class CorsConfig {
   
    private static final Logger logger = LoggerFactory.getLogger(CorsConfig.class);
   
    @Value("${security.cors.allowed-origins:https://app.appigle.com,https://admin.appigle.com,https://thankful-meadow-07b64540f.6.azurestaticapps.net}")
    private List<String> allowedOrigins;
   
    @Value("${security.cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS,PATCH}")
    private List<String> allowedMethods;
   
    @Value("${security.cors.allowed-headers:Origin,Content-Type,Accept,Authorization,X-Requested-With,X-API-Key}")
    private List<String> allowedHeaders;
   
    @Value("${security.cors.exposed-headers:Content-Disposition,X-Auth-Token,X-Request-ID}")
    private List<String> exposedHeaders;
   
    @Value("${security.cors.max-age:3600}")
    private Long maxAge;
   
    @Value("${security.cors.allow-credentials:true}")
    private Boolean allowCredentials;
   
    /**
     * Configura el filtro CORS centralizado.
     *
     * @return CorsWebFilter configurado
     */
    @Bean
    public CorsWebFilter corsWebFilter() {
        logger.info("Inicializando configuración CORS con orígenes: {}", allowedOrigins);
        
        // Configuración CORS global
        CorsConfiguration corsConfig = new CorsConfiguration();
        
        // Configuramos explícitamente los orígenes en lugar de usar setAllowedOrigins
        // para evitar problemas con credenciales cuando se usan comodines
        for (String origin : allowedOrigins) {
            corsConfig.addAllowedOrigin(origin);
        }
        
        // Agregamos todos los métodos permitidos
        for (String method : allowedMethods) {
            corsConfig.addAllowedMethod(method);
        }
        
        // Agregamos todos los headers permitidos
        for (String header : allowedHeaders) {
            corsConfig.addAllowedHeader(header);
        }
        
        // Configuramos headers expuestos
        corsConfig.setExposedHeaders(exposedHeaders);
        corsConfig.setMaxAge(maxAge);
        corsConfig.setAllowCredentials(allowCredentials);
        
        // Registro de la configuración CORS para todas las rutas
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);
        
        // APIs públicas - más permisivas
        CorsConfiguration publicConfig = new CorsConfiguration(corsConfig);
        source.registerCorsConfiguration("/api/public/**", publicConfig);
        
        // APIs de autenticación - necesitan la misma configuración permisiva
        source.registerCorsConfiguration("/api/auth/**", corsConfig);
        
        // APIs administrativas - más restrictivas
        CorsConfiguration adminConfig = new CorsConfiguration(corsConfig);
        adminConfig.setAllowedOrigins(
            allowedOrigins.stream()
                .filter(origin -> origin.contains("admin"))
                .toList()
        );
        source.registerCorsConfiguration("/api/admin/**", adminConfig);
        
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