package com.appigle.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Configuración CORS (Cross-Origin Resource Sharing) para API Gateway.
 * 
 * Esta clase proporciona configuraciones CORS específicas por entorno,
 * permitiendo una gestión segura de solicitudes entre orígenes.
 * Las configuraciones se ajustan automáticamente según el perfil activo.
 */
@Configuration
public class CorsConfig {
    private static final Logger logger = LoggerFactory.getLogger(CorsConfig.class);

    @Value("${cors.allowed-origins:https://app.appigle.com,https://admin.appigle.com}")
    private List<String> allowedOrigins;

    @Value("${cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS,PATCH}")
    private List<String> allowedMethods;

    @Value("${cors.allowed-headers:Origin,Content-Type,Accept,Authorization,X-Requested-With,X-API-Key}")
    private List<String> allowedHeaders;

    @Value("${cors.exposed-headers:Content-Disposition,X-Auth-Token,X-App-Version}")
    private List<String> exposedHeaders;

    @Value("${cors.max-age:3600}")
    private Long maxAge;

    @Value("${cors.allow-credentials:true}")
    private Boolean allowCredentials;

    private final Environment environment;

    public CorsConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * Configuración CORS para entorno de producción.
     * Implementa restricciones estrictas para orígenes permitidos.
     * 
     * @return CorsWebFilter configurado para producción
     */
    @Bean
    @Profile("prod")
    public CorsWebFilter productionCorsFilter() {
        logger.info("Inicializando configuración CORS para PRODUCCIÓN con orígenes: {}", allowedOrigins);
        
        CorsConfiguration corsConfig = createBaseCorsConfig();
        corsConfig.setAllowedOrigins(allowedOrigins);
        
        // Medidas de seguridad adicionales para producción
        corsConfig.setAllowedMethods(allowedMethods);
        corsConfig.setAllowedHeaders(allowedHeaders);
        corsConfig.setExposedHeaders(exposedHeaders);
        
        return createCorsFilter(corsConfig);
    }

    /**
     * Configuración CORS para entorno de desarrollo.
     * Menos restrictiva para facilitar las pruebas locales.
     * 
     * @return CorsWebFilter configurado para desarrollo
     */
    @Bean
    @Profile("dev")
    public CorsWebFilter developmentCorsFilter() {
        logger.info("Inicializando configuración CORS para DESARROLLO");
        
        CorsConfiguration corsConfig = createBaseCorsConfig();
        
        // Configuración más permisiva para desarrollo
        corsConfig.setAllowedOrigins(Arrays.asList(
            "http://localhost:3000", 
            "http://localhost:8080",
            "http://127.0.0.1:3000",
            "http://localhost:4200"   // Para Angular CLI
        ));
        
        corsConfig.setAllowedMethods(Arrays.asList("*"));
        corsConfig.setAllowedHeaders(Arrays.asList("*"));
        
        return createCorsFilter(corsConfig);
    }

    /**
     * Configuración CORS para entorno de pruebas.
     * Configurada para soportar pruebas automáticas.
     * 
     * @return CorsWebFilter configurado para pruebas
     */
    @Bean
    @Profile("test")
    public CorsWebFilter testCorsFilter() {
        logger.info("Inicializando configuración CORS para PRUEBAS");
        
        CorsConfiguration corsConfig = createBaseCorsConfig();
        corsConfig.setAllowedOrigins(Arrays.asList(
            "http://localhost:3000",
            "http://localhost:8080", 
            "http://test.appigle.com"
        ));
        
        return createCorsFilter(corsConfig);
    }

    /**
     * Configuración CORS por defecto si ningún perfil específico está activo.
     * 
     * @return CorsWebFilter con configuración por defecto
     */
    @Bean
    @Profile("default")
    public CorsWebFilter defaultCorsFilter() {
        logger.warn("Inicializando configuración CORS POR DEFECTO. Se recomienda definir un perfil específico.");
        
        CorsConfiguration corsConfig = createBaseCorsConfig();
        corsConfig.setAllowedOrigins(Collections.singletonList("https://app.appigle.com"));
        corsConfig.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        corsConfig.setAllowedHeaders(Arrays.asList("Origin", "Content-Type", "Accept", "Authorization"));
        
        return createCorsFilter(corsConfig);
    }

    /**
     * Crea la configuración base CORS con parámetros compartidos entre entornos.
     * 
     * @return CorsConfiguration con ajustes base
     */
    private CorsConfiguration createBaseCorsConfig() {
        CorsConfiguration corsConfig = new CorsConfiguration();
        corsConfig.setMaxAge(maxAge);
        corsConfig.setAllowCredentials(allowCredentials);
        
        // Agregar encabezados de seguridad para mitigar ataques XSS y CSRF
        corsConfig.addExposedHeader("X-XSS-Protection");
        corsConfig.addExposedHeader("X-Content-Type-Options");
        
        return corsConfig;
    }

    /**
     * Crea un filtro CORS basado en la configuración proporcionada.
     * 
     * @param corsConfig Configuración CORS a aplicar
     * @return CorsWebFilter configurado
     */
    private CorsWebFilter createCorsFilter(CorsConfiguration corsConfig) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        
        // Configuraciones específicas por ruta
        source.registerCorsConfiguration("/api/**", corsConfig);
        
        // Una configuración más restrictiva para rutas administrativas
        if (corsConfig.getAllowedOrigins() != null && !corsConfig.getAllowedOrigins().contains("*")) {
            CorsConfiguration adminConfig = new CorsConfiguration(corsConfig);
            
            // Filtrar solo orígenes administrativos
            List<String> adminOrigins = adminConfig.getAllowedOrigins().stream()
                .filter(origin -> origin.contains("admin"))
                .toList();
            
            if (!adminOrigins.isEmpty()) {
                adminConfig.setAllowedOrigins(adminOrigins);
                source.registerCorsConfiguration("/api/admin/**", adminConfig);
                logger.info("Configuración CORS específica para rutas administrativas con orígenes: {}", adminOrigins);
            }
        }
        
        // Configuración particular para endpoints públicos
        CorsConfiguration publicConfig = new CorsConfiguration(corsConfig);
        publicConfig.addAllowedMethod("GET");  // Solo permitir GET en endpoints públicos
        source.registerCorsConfiguration("/api/public/**", publicConfig);
        
        // Configuración para documentación de API
        CorsConfiguration docsConfig = new CorsConfiguration(corsConfig);
        source.registerCorsConfiguration("/v3/api-docs/**", docsConfig);
        source.registerCorsConfiguration("/swagger-ui/**", docsConfig);
        
        return new CorsWebFilter(source);
    }
    
    /**
     * Muestra información detallada sobre la configuración CORS actual para depuración.
     * 
     * @return Información de la configuración CORS
     */
    public String getConfigurationInfo() {
        StringBuilder info = new StringBuilder();
        info.append("Configuración CORS:").append("\n");
        info.append("- Perfil activo: ").append(Arrays.toString(environment.getActiveProfiles())).append("\n");
        info.append("- Orígenes permitidos: ").append(allowedOrigins).append("\n");
        info.append("- Métodos permitidos: ").append(allowedMethods).append("\n");
        info.append("- Headers permitidos: ").append(allowedHeaders).append("\n");
        info.append("- Headers expuestos: ").append(exposedHeaders).append("\n");
        info.append("- Tiempo máximo de caché: ").append(maxAge).append(" segundos\n");
        info.append("- Permite credenciales: ").append(allowCredentials);
        
        return info.toString();
    }
}