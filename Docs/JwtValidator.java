package com.appigle.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Validador de tokens JWT para la autenticación en el API Gateway.
 * 
 * Esta clase proporciona funcionalidad para validar tokens JWT,
 * verificando su firma, emisor, audiencia, expiración y tipo de token.
 * Implementa validaciones avanzadas y manejo de errores para diferentes
 * escenarios de fallo en la validación.
 */
@Component
public class JwtValidator {
    private static final Logger logger = LoggerFactory.getLogger(JwtValidator.class);
    
    // Almacenamiento en caché de tokens válidos para mejorar rendimiento 
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();
    
    private final Key signingKey;
    private final String issuer;
    private final String audience;
    private final Set<String> validTokenTypes;
    private final JwtParser jwtParser;
    private final TokenBlacklist tokenBlacklist;
    
    @Value("${security.jwt.token-cache.enabled:true}")
    private boolean cacheEnabled;
    
    @Value("${security.jwt.token-cache.duration-seconds:60}")
    private int cacheDurationSeconds;
    
    @Value("${security.jwt.clock-skew-seconds:30}")
    private int clockSkewSeconds;

    /**
     * Constructor principal con todos los parámetros necesarios.
     * 
     * @param jwtSecret Clave secreta para validar la firma JWT
     * @param issuer Issuer esperado en los tokens
     * @param audience Audience esperada en los tokens
     * @param validTokenTypes Tipos de token considerados válidos
     * @param tokenBlacklist Servicio de lista negra de tokens
     */
    public JwtValidator(
            @Value("${security.jwt.secret}") String jwtSecret,
            @Value("${security.jwt.issuer:appigle-auth}") String issuer,
            @Value("${security.jwt.audience:appigle-api}") String audience,
            @Value("${security.jwt.valid-token-types:ACCESS}") List<String> validTokenTypes,
            @Autowired(required = false) TokenBlacklist tokenBlacklist) {
        
        // Validar que la clave secreta tiene suficiente longitud
        if (jwtSecret == null || jwtSecret.length() < 32) {
            logger.warn("JWT secret key is too short! This is a security risk.");
        }
        
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.audience = audience;
        this.validTokenTypes = new HashSet<>(validTokenTypes);
        this.tokenBlacklist = tokenBlacklist;
        
        // Crear parser JWT configurado
        this.jwtParser = Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .requireIssuer(issuer)
                .requireAudience(audience)
                .setAllowedClockSkewSeconds(clockSkewSeconds)
                .build();
        
        logger.info("JWT Validator inicializado con issuer: {}, audience: {}, tipos válidos: {}, clock skew: {}s",
                issuer, audience, validTokenTypes, clockSkewSeconds);
    }
    
    /**
     * Constructor simplificado con valores por defecto.
     * 
     * @param jwtSecret Clave secreta para validar la firma JWT
     * @param issuer Issuer esperado en los tokens
     * @param audience Audience esperada en los tokens
     */
    public JwtValidator(
            @Value("${security.jwt.secret}") String jwtSecret,
            @Value("${security.jwt.issuer:appigle-auth}") String issuer,
            @Value("${security.jwt.audience:appigle-api}") String audience) {
        this(jwtSecret, issuer, audience, List.of("ACCESS"), null);
    }

    /**
     * Valida un token JWT y devuelve sus claims si es válido.
     * Comprueba la firma, emisor, audiencia, expiración y tipo de token.
     * 
     * @param token Token JWT a validar (sin el prefijo "Bearer")
     * @return Mono con los claims del token si es válido, o error si no lo es
     */
    public Mono<Claims> validateToken(String token) {
        // Comprobación de token nulo o vacío
        if (token == null || token.isBlank()) {
            return Mono.error(new JwtValidationException("Token is empty or null"));
        }
        
        // Si el token ya está en caché y todavía es válido, devolverlo
        if (cacheEnabled) {
            CachedToken cachedToken = tokenCache.get(token);
            if (cachedToken != null && !cachedToken.isExpired()) {
                return Mono.just(cachedToken.getClaims());
            }
        }
        
        // Verificar si el token está en lista negra (si hay un servicio de lista negra configurado)
        if (tokenBlacklist != null) {
            return tokenBlacklist.isBlacklisted(token)
                    .flatMap(blacklisted -> {
                        if (blacklisted) {
                            logger.warn("Token is in blacklist: {}", token);
                            return Mono.error(new JwtValidationException("Token has been revoked"));
                        }
                        return validateTokenInternal(token);
                    });
        } else {
            return validateTokenInternal(token);
        }
    }
    
    /**
     * Método interno para la validación del token.
     * 
     * @param token Token JWT a validar
     * @return Mono con los claims del token si es válido
     */
    private Mono<Claims> validateTokenInternal(String token) {
        try {
            // Parsear y validar el token
            Claims claims = jwtParser.parseClaimsJws(token).getBody();
            
            // Validar fecha de expiración de forma explícita
            // (aunque el parser también lo hace, queremos generar un error específico)
            if (claims.getExpiration().before(new Date())) {
                return Mono.error(new ExpiredJwtException(null, claims, "Token has expired"));
            }
            
            // Validar el tipo de token
            String tokenType = claims.get("tokenType", String.class);
            if (tokenType == null || !validTokenTypes.contains(tokenType)) {
                return Mono.error(new JwtValidationException("Invalid token type: " + tokenType));
            }
            
            // Validar campos adicionales del token
            if (!validateAdditionalClaims(claims)) {
                return Mono.error(new JwtValidationException("Token contains invalid claims"));
            }
            
            // Si la caché está activada, guardar el token válido
            if (cacheEnabled) {
                tokenCache.put(token, new CachedToken(claims, 
                        Instant.now().plusSeconds(cacheDurationSeconds)));
                
                // Limpiar tokens caducados de la caché (periódicamente)
                if (Math.random() < 0.1) { // 10% de probabilidad para no hacerlo en cada llamada
                    cleanupExpiredCache();
                }
            }
            
            return Mono.just(claims);
        } catch (ExpiredJwtException e) {
            logger.debug("Token validation failed: Token expired at {}", e.getClaims().getExpiration());
            return Mono.error(e);
        } catch (UnsupportedJwtException e) {
            logger.warn("Token validation failed: Unsupported JWT format");
            return Mono.error(new JwtValidationException("Unsupported JWT format: " + e.getMessage()));
        } catch (MalformedJwtException e) {
            logger.warn("Token validation failed: Malformed JWT");
            return Mono.error(new JwtValidationException("Malformed JWT: " + e.getMessage()));
        } catch (SignatureException e) {
            logger.warn("Token validation failed: Invalid signature");
            return Mono.error(new JwtValidationException("Invalid JWT signature: " + e.getMessage()));
        } catch (IllegalArgumentException e) {
            logger.warn("Token validation failed: Illegal argument - {}", e.getMessage());
            return Mono.error(new JwtValidationException("Invalid JWT: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error validating token", e);
            return Mono.error(new JwtValidationException("Validation error: " + e.getMessage()));
        }
    }
    
    /**
     * Valida campos adicionales del token según reglas de negocio.
     * 
     * @param claims Claims del token a validar
     * @return true si los claims son válidos, false en caso contrario
     */
    private boolean validateAdditionalClaims(Claims claims) {
        // Verificar que el token tenga un ID de usuario
        if (claims.get("userId") == null) {
            logger.warn("Token validation failed: Missing userId claim");
            return false;
        }
        
        // Verificar que el subject no sea nulo
        if (claims.getSubject() == null || claims.getSubject().isBlank()) {
            logger.warn("Token validation failed: Missing or empty subject");
            return false;
        }
        
        // Verificar que la fecha de emisión no sea futura
        if (claims.getIssuedAt() != null && 
                claims.getIssuedAt().after(new Date(System.currentTimeMillis() + 
                                                    clockSkewSeconds * 1000))) {
            logger.warn("Token validation failed: Future issuedAt date");
            return false;
        }
        
        // Verificar que tiene al menos un rol asignado
        Object roles = claims.get("roles");
        if (roles == null) {
            logger.debug("Token without roles claim");
        }
        
        return true;
    }
    
    /**
     * Extraer información clave del token para loggear información útil.
     * 
     * @param token Token a examinar
     * @return String con información resumida del token
     */
    public String getTokenInfo(String token) {
        try {
            Claims claims = jwtParser.parseClaimsJws(token).getBody();
            return String.format("Token[subject: %s, expiration: %s, type: %s]", 
                claims.getSubject(), 
                claims.getExpiration(),
                claims.get("tokenType"));
        } catch (Exception e) {
            return "Invalid token: " + e.getMessage();
        }
    }
    
    /**
     * Limpia entradas caducadas de la caché de tokens.
     */
    private void cleanupExpiredCache() {
        Instant now = Instant.now();
        Set<String> expiredKeys = tokenCache.entrySet().stream()
                .filter(entry -> entry.getValue().isExpired(now))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        
        if (!expiredKeys.isEmpty()) {
            expiredKeys.forEach(tokenCache::remove);
            logger.debug("Cleaned {} expired tokens from cache", expiredKeys.size());
        }
    }
    
    /**
     * Excepción específica para errores de validación JWT.
     */
    public static class JwtValidationException extends RuntimeException {
        /**
         * Constructor con mensaje de error.
         * 
         * @param message Mensaje descriptivo del error
         */
        public JwtValidationException(String message) {
            super(message);
        }
        
        /**
         * Constructor con mensaje y causa original.
         * 
         * @param message Mensaje descriptivo del error
         * @param cause Excepción original que causó el error
         */
        public JwtValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    /**
     * Clase interna para almacenar tokens en caché con su tiempo de expiración.
     */
    private static class CachedToken {
        private final Claims claims;
        private final Instant expiresAt;
        
        public CachedToken(Claims claims, Instant expiresAt) {
            this.claims = claims;
            this.expiresAt = expiresAt;
        }
        
        public Claims getClaims() {
            return claims;
        }
        
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
        
        public boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }
}