package com.appigle.gateway.config;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;

import com.appigle.gateway.security.JwtValidator;
import com.appigle.gateway.security.TokenBlacklist;

import reactor.core.publisher.Mono;

/**
 * Configuración de seguridad para el API Gateway de AppIgle.
 *
 * Esta clase define las políticas de seguridad a nivel de Spring Security,
 * complementando el filtro de autenticación personalizado. Define rutas públicas,
 * políticas de CSRF, configuraciones de cabeceras de seguridad y manejo de errores
 * de autenticación.
 * 
 * NOTA: La configuración CORS ha sido centralizada en CorsConfig.java
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${security.public-paths:/actuator/health,/actuator/info,/fallback/**,/api/auth/login,/api/auth/register,/api/email-verification/verify}")
    private List<String> publicPaths;
    
    @Value("${security.api-docs-paths:/swagger-ui/**,/v3/api-docs/**}")
    private List<String> apiDocsPaths;
    
    @Value("${security.actuator-paths:/actuator/**}")
    private List<String> actuatorPaths;
    
    @Value("${security.csrf.enabled:true}")
    private boolean csrfEnabled;
    
    @Value("${security.content-security-policy:default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:;}")
    private String contentSecurityPolicy;
    
    /**
     * Configura el validador de tokens JWT.
     *
     * @param jwtSecret Clave secreta para verificar la firma de los tokens
     * @param issuer Emisor esperado en los tokens
     * @param audience Audiencia esperada en los tokens
     * @param validTokenTypes Tipos de token válidos (por ejemplo, "ACCESS")
     * @param tokenBlacklist Servicio de lista negra de tokens (opcional)
     * @return Bean configurado de JwtValidator
     */
    @Bean
    public JwtValidator jwtValidator(
            @Value("${security.jwt.secret}") String jwtSecret,
            @Value("${security.jwt.issuer:appigle-auth}") String issuer,
            @Value("${security.jwt.audience:appigle-api}") String audience,
            @Value("${security.jwt.valid-token-types:ACCESS}") List<String> validTokenTypes,
            @Autowired(required = false) TokenBlacklist tokenBlacklist) {
        
        logger.info("Inicializando validador JWT con issuer: {}, audience: {}", issuer, audience);
        return new JwtValidator(jwtSecret, issuer, audience, validTokenTypes, tokenBlacklist);
    }
    
    /**
     * Configuración principal de seguridad.
     * Define políticas de seguridad para todas las rutas.
     *
     * @param http Configuración de seguridad HTTP
     * @return Cadena de filtros de seguridad configurada
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        logger.info("Configurando seguridad principal del API Gateway");
        logger.debug("Rutas públicas configuradas: {}", publicPaths);
        
        ServerHttpSecurity.AuthorizeExchangeSpec authorizeExchange = http
                .securityMatcher(ServerWebExchangeMatchers.anyExchange())
                .authorizeExchange();
        
        // Configurar todas las reglas antes de anyExchange()
        
        // Permitir rutas públicas explícitas
        authorizeExchange.pathMatchers(publicPaths.toArray(new String[0])).permitAll();
        
        // Permitir acceso a documentación API según configuración
        if (apiDocsPaths != null && !apiDocsPaths.isEmpty()) {
            authorizeExchange.pathMatchers(apiDocsPaths.toArray(new String[0])).permitAll();
        }
        
        // Permitir OPTIONS para verificar CORS
        authorizeExchange.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll();
        
        // Solo permitir POST para login y registro
        authorizeExchange.pathMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll();
        authorizeExchange.pathMatchers(HttpMethod.GET, "/api/auth/login", "/api/auth/register").denyAll();
        
        // Restringir métodos para API pública
        authorizeExchange.pathMatchers(HttpMethod.GET, "/api/public/**").permitAll();
        authorizeExchange.pathMatchers(HttpMethod.POST, "/api/public/**", "/api/public/feedback").permitAll();
        authorizeExchange.pathMatchers(HttpMethod.PUT, "/api/public/**").denyAll();
        authorizeExchange.pathMatchers(HttpMethod.DELETE, "/api/public/**").denyAll();
        authorizeExchange.pathMatchers(HttpMethod.PATCH, "/api/public/**").denyAll();
        
        // Cualquier otra ruta requiere autenticación que será manejada por nuestro filtro personalizado
        // IMPORTANTE: anyExchange() debe ser la última regla
        authorizeExchange.anyExchange().permitAll();
        
        logger.debug("Rutas de documentación API: {}", apiDocsPaths);
        logger.debug("Rutas de Actuator: {}", actuatorPaths);
        
        // Completar configuración
        http.exceptionHandling(exceptionHandling -> exceptionHandling
                .authenticationEntryPoint(authenticationEntryPoint())
                .accessDeniedHandler((exchange, ex) -> {
                    logger.warn("Acceso denegado: {}", ex.getMessage());
                    return Mono.fromRunnable(() -> {
                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                    });
                })
            );
        
        // CSRF - Deshabilitado para APIs pero habilitado para aplicaciones web si se configura
        if (!csrfEnabled) {
            http.csrf(ServerHttpSecurity.CsrfSpec::disable);
            logger.info("Protección CSRF deshabilitada por configuración");
        } else {
            http.csrf(csrf -> csrf
                .requireCsrfProtectionMatcher(exchange -> {
                    // Solo requiere CSRF para rutas específicas de aplicaciones web
                    String path = exchange.getRequest().getURI().getPath();
                    boolean isApiPath = path.startsWith("/api/");
                    boolean isPublicPath = publicPaths.stream().anyMatch(path::startsWith);
                    // No requiere CSRF para APIs y rutas públicas
                    if (isApiPath || isPublicPath) {
                        return MatchResult.notMatch();
                    }
                    return MatchResult.match();
                })
            );
            logger.info("Protección CSRF habilitada con excepciones para APIs");
        }
        
        // Desactivar autenticación básica y formulario - usamos JWT
        http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable);
        
        // Configurar logout
        configureLogout(http);
        
        // Configurar cabeceras de seguridad
        configureSecurityHeaders(http);
        
        return http.build();
    }
    
    /**
     * Configuración especial para endpoints de monitoreo.
     * Aplica reglas de seguridad específicas a las rutas de Actuator.
     *
     * @param http Configuración de seguridad HTTP
     * @return Cadena de filtros de seguridad para Actuator
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public SecurityWebFilterChain actuatorSecurityWebFilterChain(ServerHttpSecurity http) {
        logger.info("Configurando seguridad para endpoints de Actuator");
        
        return http
            .securityMatcher(ServerWebExchangeMatchers.pathMatchers(actuatorPaths.toArray(new String[0])))
            .authorizeExchange(exchanges -> exchanges
                // Permitir acceso público a health e info
                .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                // Requiere autenticación para otros endpoints de actuator
                .anyExchange().authenticated()
            )
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .build();
    }
    
    /**
     * Configura cabeceras de seguridad para protección contra vulnerabilidades comunes.
     *
     * @param http Configuración de seguridad HTTP
     */
    private void configureSecurityHeaders(ServerHttpSecurity http) {
        // Configuración de cabeceras de seguridad para protección contra vulnerabilidades comunes
        http.headers(headers -> headers
            // Configuración de X-Frame-Options para prevenir ataques de clickjacking
            .frameOptions(frameOptions -> frameOptions
                .mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
            
            // Configuración de Content-Security-Policy para mitigar ataques XSS
            .contentSecurityPolicy(csp -> csp
                .policyDirectives(contentSecurityPolicy))
            
            // Configuración de Referrer-Policy para controlar información enviada en el encabezado Referer
            .referrerPolicy(referrerPolicy -> referrerPolicy
                .policy(ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            
            // Deshabilitar la caché para información sensible
            .cache(cache -> cache.disable())
            
            // Configuración de HTTP Strict Transport Security para forzar conexiones HTTPS
            .hsts(hsts -> hsts
                .includeSubdomains(true)
                .maxAge(Duration.ofDays(365)))
            
            // Prevención de MIME-sniffing
            .contentTypeOptions(contentTypeOptions -> {})
            
            // Configuración de X-XSS-Protection para Spring WebFlux
            // En versiones recientes esto se puede configurar de manera simple
            .xssProtection(xss -> {})
        );
        logger.debug("Cabeceras de seguridad configuradas con CSP: {}", contentSecurityPolicy);
    }
    
    /**
     * Configura el manejo de logout.
     *
     * @param http Configuración de seguridad HTTP
     */
    private void configureLogout(ServerHttpSecurity http) {
        http.logout(logout -> logout
            .logoutUrl("/api/auth/logout")
            .requiresLogout(ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/api/auth/logout"))
            .logoutSuccessHandler(logoutSuccessHandler())
        );
    }
    
    /**
     * Maneja fallos de autenticación con respuestas estructuradas en JSON.
     *
     * @return Manejador de fallos de autenticación
     */
    @Bean
    public ServerAuthenticationFailureHandler authenticationFailureHandler() {
        return (webFilterExchange, exception) -> {
            ServerWebExchange exchange = webFilterExchange.getExchange();
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            
            logger.warn("Fallo de autenticación: {}", exception.getMessage());
            
            return Mono.empty();
        };
    }
    
    /**
     * Configura el punto de entrada para errores de autenticación.
     *
     * @return Punto de entrada de autenticación
     */
    @Bean
    public ServerAuthenticationEntryPoint authenticationEntryPoint() {
        return (exchange, ex) -> {
            logger.warn("Acceso no autorizado: {}", ex.getMessage());
            
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().add("WWW-Authenticate", "Bearer");
            
            return Mono.empty();
        };
    }
    
    /**
     * Maneja el proceso de logout exitoso.
     *
     * @return Manejador de logout exitoso
     */
    @Bean
    public ServerLogoutSuccessHandler logoutSuccessHandler() {
        return (webFilterExchange, authentication) -> {
            String userId = authentication != null && authentication.getPrincipal() != null ?
                           authentication.getPrincipal().toString() : "unknown";
                           
            logger.info("Logout exitoso para usuario: {}", userId);
            
            ServerWebExchange exchange = webFilterExchange.getExchange();
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        };
    }
}