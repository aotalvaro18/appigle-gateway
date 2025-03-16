package com.appigle.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validador simple de tokens JWT para autenticación.
 * 
 * Proporciona funcionalidades para:
 * - Validar tokens JWT
 * - Extraer información de usuario de los tokens
 * - Implementar caché para mejorar rendimiento
 * - Integración con Azure Key Vault para obtener el secreto JWT
 */
@Component
@Profile("azure-recovery")
public class SimpleJwtValidator {
    
    private static final Logger logger = LoggerFactory.getLogger(SimpleJwtValidator.class);
    
    private final SecretKey key;
    private final Map<String, Claims> tokenCache = new ConcurrentHashMap<>();
    
    /**
     * Constructor que configura el validador con la clave secreta.
     * 
     * La clave se obtiene de Azure Key Vault si está configurado,
     * o de las propiedades locales como fallback.
     * 
     * @param secret Clave secreta para verificar firmas JWT, obtenida automáticamente
     *              de Azure Key Vault usando el nombre "jwt-secret"
     */
    public SimpleJwtValidator(@Value("${jwt-secret:defaultSecretKeyForDevelopmentOnly}") String secret) {
        logger.info("Inicializando validador JWT con secreto de Key Vault");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        
        // No imprimimos el secreto por seguridad, solo confirmamos que se ha inicializado
        logger.debug("Secreto JWT inicializado correctamente, longitud: {}", secret.length());
    }
    
    /**
     * Valida un token JWT y devuelve sus claims si es válido.
     * 
     * @param token Token JWT a validar (sin el prefijo "Bearer")
     * @return Mono con los claims del token si es válido, o vacío si no lo es
     */
    public Mono<Claims> validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return Mono.empty();
        }
        
        // Verificar en caché primero para mejorar rendimiento
        if (tokenCache.containsKey(token)) {
            Claims cachedClaims = tokenCache.get(token);
            if (isNotExpired(cachedClaims)) {
                return Mono.just(cachedClaims);
            } else {
                tokenCache.remove(token);
            }
        }
        
        return Mono.fromCallable(() -> {
            try {
                // Para JJWT 0.11.5
                Claims claims = Jwts.parserBuilder()
                        .setSigningKey(key)
                        .build()
                        .parseClaimsJws(token)
                        .getBody();
                
                if (isNotExpired(claims)) {
                    tokenCache.put(token, claims);
                    return claims;
                }
                
                return null;
            } catch (Exception e) {
                logger.debug("Error validando token: {}", e.getMessage());
                return null;
            }
        }).onErrorResume(e -> {
            logger.warn("Error procesando token JWT: {}", e.getMessage());
            return Mono.empty();
        });
    }
    
    /**
     * Verifica que un token no ha expirado.
     * 
     * @param claims Claims del token a verificar
     * @return true si el token no ha expirado, false en caso contrario
     */
    private boolean isNotExpired(Claims claims) {
        Date expiration = claims.getExpiration();
        return expiration != null && expiration.after(new Date());
    }
    
    /**
     * Extrae información del usuario a partir de un token JWT.
     * 
     * @param token Token JWT a procesar
     * @return Mono con un mapa de información del usuario
     */
    public Mono<Map<String, String>> extractUserInfo(String token) {
        return validateToken(token)
                .map(claims -> {
                    Map<String, String> userInfo = new HashMap<>();
                    userInfo.put("userId", claims.get("sub", String.class));
                    userInfo.put("username", claims.get("username", String.class));
                    userInfo.put("role", claims.get("role", String.class));
                    logger.debug("Información de usuario extraída: userId={}, username={}", 
                        userInfo.get("userId"), userInfo.get("username"));
                    return userInfo;
                });
    }
    
    /**
     * Limpia manualmente la caché de tokens.
     * Útil para pruebas o cuando se desea forzar revalidación.
     */
    public void clearCache() {
        int size = tokenCache.size();
        tokenCache.clear();
        logger.info("Caché de tokens limpiada: {} entradas eliminadas", size);
    }
}