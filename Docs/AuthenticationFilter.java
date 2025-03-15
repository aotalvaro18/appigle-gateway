package com.appigle.gateway.filter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;

import com.appigle.gateway.security.JwtValidator;

import io.jsonwebtoken.Claims;
import reactor.core.publisher.Mono;

/**
 * Filtro global de autenticación para API Gateway.
 * 
 * Este filtro intercepta todas las solicitudes entrantes, verifica la
 * autenticación mediante token JWT para rutas protegidas, y propaga la
 * información de identidad a los servicios downstream mediante headers.
 */
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {
    private static final Logger logger = LoggerFactory.getLogger(AuthenticationFilter.class);

    private final JwtValidator jwtValidator;
    private final AntPathMatcher pathMatcher;
    private final Predicate<ServerHttpRequest> publicEndpointsPredicate;
    private final Set<String> sensitiveHeaders;
    
    @Value("${security.auth.propagate-tenant-info:true}")
    private boolean propagateTenantInfo;
    
    @Value("${security.auth.propagate-roles:true}")
    private boolean propagateRoles;
    
    @Value("${security.auth.propagate-permissions:true}")
    private boolean propagatePermissions;
    
    @Value("${security.auth.header-prefix:X-}")
    private String headerPrefix;
    
    @Value("${security.auth.user-id-claim:userId}")
    private String userIdClaim;

    /**
     * Constructor principal que configura el filtro con las dependencias y configuraciones necesarias.
     * 
     * @param jwtValidator Validador de tokens JWT
     * @param publicPaths Lista de rutas públicas que no requieren autenticación
     */
    public AuthenticationFilter(
            JwtValidator jwtValidator,
            @Value("${security.auth.public-paths:/api/auth/login,/api/auth/register,/api/auth/refresh-token," + 
                  "/api/email-verification/verify,/actuator/health,/actuator/info,/swagger-ui/**,/v3/api-docs/**}") 
            String[] publicPaths) {
        
        this.jwtValidator = jwtValidator;
        this.pathMatcher = new AntPathMatcher();
        
        // Lista de rutas que no requieren autenticación
        logger.info("Configurando rutas públicas: {}", Arrays.toString(publicPaths));
        
        // Predicado para determinar si una ruta es pública
        this.publicEndpointsPredicate = request -> {
            String path = request.getURI().getPath();
            return Arrays.stream(publicPaths)
                    .anyMatch(pattern -> pathMatcher.match(pattern, path));
        };
        
        // Headers sensibles que no deben propagarse a los servicios internos
        this.sensitiveHeaders = new HashSet<>(Arrays.asList(
                "authorization", "cookie", "set-cookie"
        ));
        
        logger.info("Filtro de autenticación inicializado con prefix de header: {}", headerPrefix);
    }

    /**
     * Método principal del filtro que procesa cada solicitud.
     * 
     * @param exchange Intercambio de solicitud/respuesta
     * @param chain Cadena de filtros
     * @return Mono que se completa cuando el filtro ha terminado su trabajo
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        
        // Registrar información de la solicitud para diagnóstico
        if (logger.isDebugEnabled()) {
            logger.debug("Processing request: {} {}", request.getMethod(), path);
        }
        
        // Omitir autenticación para endpoints públicos
        if (publicEndpointsPredicate.test(request)) {
            logger.debug("Skipping authentication for public endpoint: {}", path);
            return chain.filter(exchange);
        }
        
        // Verificar token de autorización
        List<String> authHeaders = request.getHeaders().getOrEmpty("Authorization");
        if (authHeaders.isEmpty() || !authHeaders.get(0).startsWith("Bearer ")) {
            logger.debug("Missing or invalid Authorization header for path: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().add("WWW-Authenticate", "Bearer");
            return exchange.getResponse().setComplete();
        }
        
        String token = authHeaders.get(0).substring(7);
        
        // Validar token y procesar
        return jwtValidator.validateToken(token)
                .flatMap(claims -> {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Token válido para usuario: {}, accediendo a: {}", 
                                claims.getSubject(), path);
                    }
                    
                    // Agregar claims como encabezados para downstream services
                    ServerHttpRequest modifiedRequest = enrichRequestWithClaims(request, claims);
                    
                    // Continuar con la cadena de filtros
                    return chain.filter(exchange.mutate().request(modifiedRequest).build());
                })
                .onErrorResume(e -> {
                    // Manejo centralizado de errores de autenticación
                    logger.warn("JWT validation failed for path {}: {}", path, e.getMessage());
                    
                    // Configurar respuesta de error
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    exchange.getResponse().getHeaders().add("WWW-Authenticate", "Bearer error=\"invalid_token\"");
                    
                    return exchange.getResponse().setComplete();
                });
    }
    
    /**
     * Enriquece la solicitud con claims del token JWT como headers.
     * 
     * @param request Solicitud original
     * @param claims Claims extraídos del token JWT
     * @return Solicitud modificada con headers adicionales
     */
    private ServerHttpRequest enrichRequestWithClaims(ServerHttpRequest request, Claims claims) {
        ServerHttpRequest.Builder builder = request.mutate();
        
        // Eliminar headers sensibles
        sensitiveHeaders.forEach(header -> builder.headers(headers -> headers.remove(header)));
        
        // Agregar claims básicos como headers
        builder.header(headerPrefix + "User-Id", getClaimAsString(claims, userIdClaim));
        builder.header(headerPrefix + "User-Email", claims.getSubject());
        
        // Agregar roles si está habilitado
        if (propagateRoles) {
            addListClaimAsHeader(builder, claims, "roles", headerPrefix + "User-Roles");
        }
        
        // Agregar permisos si está habilitado
        if (propagatePermissions) {
            addListClaimAsHeader(builder, claims, "permissions", headerPrefix + "User-Permissions");
        }
        
        // Agregar información de tenant si está habilitado
        if (propagateTenantInfo) {
            addTenantInfoAsHeaders(builder, claims);
        }
        
        // Añadir metadata adicional que pueda ser útil
        builder.header(headerPrefix + "Token-Expiration", String.valueOf(claims.getExpiration().getTime()));
        
        return builder.build();
    }
    
    /**
     * Añade información de tenant como headers.
     * 
     * @param builder Builder de la solicitud
     * @param claims Claims del token JWT
     */
    private void addTenantInfoAsHeaders(ServerHttpRequest.Builder builder, Claims claims) {
        Object tenant = claims.get("tenant");
        if (!(tenant instanceof Map)) {
            return;
        }
        
        Map<String, Object> tenantMap = (Map<String, Object>) tenant;
        
        // Extraer información de organización si existe
        extractNestedEntityInfo(builder, tenantMap, "organization", "Organization");
        
        // Extraer información de iglesia si existe
        extractNestedEntityInfo(builder, tenantMap, "church", "Church");
    }
    
    /**
     * Extrae información de una entidad anidada en la estructura de tenant.
     * 
     * @param builder Builder de la solicitud
     * @param parentMap Mapa padre que contiene la entidad
     * @param entityKey Clave de la entidad en el mapa padre
     * @param headerSuffix Sufijo para los headers generados
     */
    private void extractNestedEntityInfo(ServerHttpRequest.Builder builder, 
                                         Map<String, Object> parentMap, 
                                         String entityKey, 
                                         String headerSuffix) {
        if (!parentMap.containsKey(entityKey) || !(parentMap.get(entityKey) instanceof Map)) {
            return;
        }
        
        Map<String, Object> entityMap = (Map<String, Object>) parentMap.get(entityKey);
        
        // Extraer y agregar ID
        if (entityMap.containsKey("id")) {
            builder.header(headerPrefix + headerSuffix + "-Id", entityMap.get("id").toString());
        }
        
        // Extraer y agregar código
        if (entityMap.containsKey("code")) {
            builder.header(headerPrefix + headerSuffix + "-Code", entityMap.get("code").toString());
        }
        
        // Extraer y agregar nombre si existe
        if (entityMap.containsKey("name")) {
            builder.header(headerPrefix + headerSuffix + "-Name", entityMap.get("name").toString());
        }
    }
    
    /**
     * Añade un claim de tipo lista como header.
     * 
     * @param builder Builder de la solicitud
     * @param claims Claims del token
     * @param claimName Nombre del claim
     * @param headerName Nombre del header a generar
     */
    private void addListClaimAsHeader(ServerHttpRequest.Builder builder, 
                                     Claims claims, 
                                     String claimName, 
                                     String headerName) {
        Object claimValue = claims.get(claimName);
        if (claimValue instanceof List) {
            List<?> listValue = (List<?>) claimValue;
            if (!listValue.isEmpty()) {
                String headerValue = listValue.stream()
                        .map(Object::toString)
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
                
                if (!headerValue.isEmpty()) {
                    builder.header(headerName, headerValue);
                }
            }
        }
    }
    
    /**
     * Obtiene un claim como String, manejando posibles tipos diferentes.
     * 
     * @param claims Claims del token
     * @param claimName Nombre del claim
     * @return Valor del claim como String o null si no existe
     */
    private String getClaimAsString(Claims claims, String claimName) {
        Object value = claims.get(claimName);
        return value != null ? value.toString() : null;
    }

    /**
     * Define el orden de ejecución del filtro en la cadena de filtros.
     * 
     * @return Orden de ejecución (valor más bajo = mayor prioridad)
     */
    @Override
    public int getOrder() {
        return -100; // Alta prioridad, ejecutar antes de otros filtros
    }
}