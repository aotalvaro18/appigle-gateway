package com.appigle.gateway.filter;

import com.appigle.gateway.security.SimpleJwtValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Filtro de autenticación para verificar tokens JWT.
 * 
 * Este filtro:
 * - Permite el acceso a rutas públicas sin autenticación
 * - Permite solicitudes OPTIONS (preflight CORS) sin autenticación
 * - Verifica tokens JWT para rutas protegidas
 * - Propaga información del usuario en headers para servicios downstream
 * - Rechaza solicitudes no autorizadas
 */
@Component
@Profile("azure")
public class SimpleAuthenticationFilter implements WebFilter {
    
    private static final Logger logger = LoggerFactory.getLogger(SimpleAuthenticationFilter.class);
    
    private final SimpleJwtValidator jwtValidator;
    
    private final List<String> publicPaths = List.of(
            "/actuator/health",
            "/actuator/info",
            "/api/auth/login",
            "/api/auth/register",
            "/fallback",
            "/api/email-verification/verify");
            
    @Autowired
    public SimpleAuthenticationFilter(SimpleJwtValidator jwtValidator) {
        this.jwtValidator = jwtValidator;
        logger.info("Filtro de autenticación inicializado con {} rutas públicas", publicPaths.size());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        
        // Manejar específicamente solicitudes OPTIONS para CORS
    if (request.getMethod() == HttpMethod.OPTIONS) {
        logger.info("Permitiendo solicitud OPTIONS para CORS en: {}", path);
        
        // Agregar headers CORS directamente
        ServerHttpResponse response = exchange.getResponse();
        String origin = request.getHeaders().getOrigin();
        
        if (origin != null && origin.equals("https://thankful-meadow-07b64540f.6.azurestaticapps.net")) {
            response.getHeaders().add("Access-Control-Allow-Origin", origin);
            response.getHeaders().add("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS,PATCH");
            response.getHeaders().add("Access-Control-Allow-Headers", "Origin,Content-Type,Accept,Authorization,X-Requested-With,X-API-Key");
            response.getHeaders().add("Access-Control-Allow-Credentials", "true");
            response.getHeaders().add("Access-Control-Max-Age", "3600");
            response.setStatusCode(HttpStatus.OK);
            return Mono.empty();
        }
        
        // Si el origen no es reconocido, continuar con la cadena
        return chain.filter(exchange);
    }
        
        // Omitir autenticación para endpoints públicos
        if (isPublicPath(path)) {
            logger.debug("Ruta pública accedida: {}", path);
            return chain.filter(exchange);
        }
        
        // Verificar token de autorización
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            logger.debug("Solicitud rechazada: falta token en ruta protegida: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        
        String token = authHeader.substring(7);
        
        return jwtValidator.extractUserInfo(token)
                .flatMap(userInfo -> {
                    logger.debug("Usuario autenticado: {} accediendo a {}", 
                        userInfo.get("username"), path);
                    
                    // Agregar información del usuario como headers
                    ServerHttpRequest mutatedRequest = mutateRequest(request, userInfo);
                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                })
                .switchIfEmpty(Mono.defer(() -> {
                    logger.warn("Token inválido al acceder a: {}", path);
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }));
    }
    
    /**
     * Determina si una ruta es pública (no requiere autenticación).
     * 
     * @param path Ruta a verificar
     * @return true si la ruta es pública, false si requiere autenticación
     */
    private boolean isPublicPath(String path) {
        return publicPaths.stream().anyMatch(path::startsWith);
    }
    
    /**
     * Modifica la solicitud agregando información del usuario como headers.
     * 
     * @param request Solicitud original
     * @param userInfo Información del usuario extraída del token
     * @return Solicitud modificada con headers adicionales
     */
    private ServerHttpRequest mutateRequest(ServerHttpRequest request, Map<String, String> userInfo) {
        return request.mutate()
                .header("X-User-Id", userInfo.get("userId"))
                .header("X-Username", userInfo.get("username"))
                .header("X-User-Role", userInfo.get("role"))
                .header("X-Gateway-Source", "appigle-gateway")
                .build();
    }
}