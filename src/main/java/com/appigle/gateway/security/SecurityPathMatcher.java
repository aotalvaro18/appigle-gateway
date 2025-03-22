package com.appigle.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Clase utilitaria para determinar si una ruta es pública o protegida.
 * Centraliza la lógica de coincidencia de rutas para seguridad.
 */
@Component
public class SecurityPathMatcher {
    
    private static final Logger logger = LoggerFactory.getLogger(SecurityPathMatcher.class);
    
    private static final List<String> PUBLIC_ENDPOINTS = Arrays.asList(
        "/actuator/health",
        "/actuator/info",
        "/fallback",
        "/test"
    );
    
    private static final Map<HttpMethod, List<String>> METHOD_SPECIFIC_PUBLIC_PATHS = new HashMap<>();
    
    static {
        // Rutas públicas para POST
        METHOD_SPECIFIC_PUBLIC_PATHS.put(HttpMethod.POST, Arrays.asList(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/verify-email",
            "/api/auth/refresh-token",
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/api/auth/logout"
        ));
        
        // Rutas públicas para GET
        METHOD_SPECIFIC_PUBLIC_PATHS.put(HttpMethod.GET, Arrays.asList(
            "/api/auth/google",
            "/api/email-verification/verify"
        ));
        
        // Rutas públicas para OPTIONS (CORS)
        METHOD_SPECIFIC_PUBLIC_PATHS.put(HttpMethod.OPTIONS, Arrays.asList("/**"));
    }
    
    /**
     * Determina si una ruta es pública basándose en la ruta y el método HTTP.
     *
     * @param exchange El intercambio de la solicitud
     * @return true si la ruta es pública, false si requiere autenticación
     */
    public boolean isPublicPath(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        HttpMethod method = exchange.getRequest().getMethod();
        
        // Verificar primero rutas públicas genéricas
        for (String publicPath : PUBLIC_ENDPOINTS) {
            if (path.startsWith(publicPath)) {
                logger.debug("Ruta pública genérica encontrada: {}", path);
                return true;
            }
        }
        
        // Verificar rutas específicas por método HTTP
        if (method != null && METHOD_SPECIFIC_PUBLIC_PATHS.containsKey(method)) {
            List<String> methodSpecificPaths = METHOD_SPECIFIC_PUBLIC_PATHS.get(method);
            for (String methodPath : methodSpecificPaths) {
                if (path.startsWith(methodPath)) {
                    logger.debug("Ruta pública específica para método {} encontrada: {}", method, path);
                    return true;
                }
            }
        }
        
        logger.debug("Ruta no encontrada como pública: {} {}", method, path);
        return false;
    }
}