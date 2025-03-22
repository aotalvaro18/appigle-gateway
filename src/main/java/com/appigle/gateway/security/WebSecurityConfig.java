package com.appigle.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;

/**
 * Configuración centralizada de seguridad para la aplicación Gateway.
 * 
 * Define políticas de seguridad, rutas públicas, y configuración CSRF
 * siguiendo las mejores prácticas para entornos empresariales.
 */
@Configuration
@EnableWebFluxSecurity
public class WebSecurityConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(WebSecurityConfig.class);
    
    /**
     * Configura la cadena de filtros de seguridad para la aplicación.
     * 
     * @param http la configuración base de seguridad HTTP
     * @return la cadena de filtros de seguridad configurada
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        logger.info("Configurando filtros de seguridad para la aplicación Gateway");
        
        return http
            // Configuración CSRF - Deshabilitamos para APIs REST pero mantenemos para aplicaciones web tradicionales
            .csrf(csrf -> csrf
                // Deshabilitamos CSRF para APIs REST
                .disable()
                // Si en el futuro necesitas habilitar CSRF para ciertas rutas:
                // .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
            )
            
            // Configuración de autorización por rutas
            .authorizeExchange(exchanges -> exchanges
                // Permitimos solicitudes OPTIONS para CORS sin autenticación
                .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Permitir acceso a endpoints de prueba
                .pathMatchers("/test/**").permitAll()
                
                // Rutas públicas de autenticación
                .pathMatchers("/api/auth/login", "/api/auth/register", "/api/auth/verify-email", 
                              "/api/auth/refresh-token", "/api/auth/forgot-password", 
                              "/api/auth/reset-password", "/api/auth/google/**").permitAll()
                
                // Endpoints de monitoreo y salud
                .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                
                // Documentación de API
                .pathMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()
                
                // Fallbacks para circuit breakers
                .pathMatchers("/fallback/**").permitAll()
                
                // Resto de endpoints requieren autenticación
                .anyExchange().authenticated()
            )
            
            // Deshabilitamos login basado en formularios (usamos JWT)
            .formLogin(form -> form.disable())
            
            // Deshabilitamos HTTP Basic Auth (usamos JWT)
            .httpBasic(basic -> basic.disable())
            
            // Configuración de headers de seguridad
            .headers(headers -> headers
                .frameOptions().disable()  // Permite embedding en iframes si es necesario
                .xssProtection().disable() // Moderno: los navegadores manejan XSS
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'self'; img-src 'self' data:; script-src 'self'")
                )
            )
            
            .build();
    }
}