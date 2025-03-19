package com.appigle.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Filtro para el registro detallado de solicitudes HTTP.
 * 
 * Proporciona información completa sobre cada solicitud:
 * - ID único para correlación entre logs
 * - Método y URI completa
 * - Dirección IP del cliente
 * - Código de estado de la respuesta
 * - Duración de la solicitud
 * - Detalles adicionales para errores
 */
@Component
@Profile("azure")
public class LoggingFilter implements WebFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(LoggingFilter.class);
    public static final String REQUEST_ID_ATTRIBUTE = "requestId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Generar un ID único para esta solicitud para correlación entre logs
        String requestId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();
        
        // Agregar información de inicio de solicitud
        logger.info("[{}] Request started: {} {} from {}",
                requestId,
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI(),
                exchange.getRequest().getRemoteAddress());
        
        // Registrar headers importantes (solo en nivel debug)
        if (logger.isDebugEnabled()) {
            exchange.getRequest().getHeaders().forEach((name, values) -> {
                if (isImportantHeader(name)) {
                    logger.debug("[{}] Header {}: {}", requestId, name, String.join(", ", values));
                }
            });
        }

        // Almacenar el requestId en los atributos para que esté disponible en toda la cadena
        exchange.getAttributes().put(REQUEST_ID_ATTRIBUTE, requestId);
        
        // Agregar el ID de solicitud como header de respuesta para facilitar la depuración del cliente
        exchange.getResponse().getHeaders().add("X-Request-ID", requestId);

        return chain.filter(exchange)
                .doFinally(signalType -> {
                    long duration = System.currentTimeMillis() - startTime;
                    HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
                    
                    // Log diferente según el resultado de la solicitud
                    if (statusCode == null) {
                        logger.error("[{}] Request ended without status code: {} {} - Duration: {}ms",
                                requestId,
                                exchange.getRequest().getMethod(),
                                exchange.getRequest().getURI(),
                                duration);
                    } else {
                        int status = statusCode.value();
                        if (status >= 500) {
                            // Error del servidor - Log a nivel de error
                            logger.error("[{}] Request failed: {} {} - Status: {} - Duration: {}ms",
                                    requestId,
                                    exchange.getRequest().getMethod(),
                                    exchange.getRequest().getURI(),
                                    status,
                                    duration);
                        } else if (status >= 400) {
                            // Error del cliente - Log a nivel de warning
                            logger.warn("[{}] Request rejected: {} {} - Status: {} - Duration: {}ms",
                                    requestId,
                                    exchange.getRequest().getMethod(),
                                    exchange.getRequest().getURI(),
                                    status,
                                    duration);
                        } else {
                            // Éxito - Log a nivel de info
                            logger.info("[{}] Request completed: {} {} - Status: {} - Duration: {}ms",
                                    requestId,
                                    exchange.getRequest().getMethod(),
                                    exchange.getRequest().getURI(),
                                    status,
                                    duration);
                        }
                    }
                });
    }

    /**
     * Determina si un header es importante para el logging.
     * Evita registrar información sensible como tokens de autenticación.
     * 
     * @param headerName Nombre del header a evaluar
     * @return true si el header debe ser incluido en los logs
     */
    private boolean isImportantHeader(String headerName) {
        String nameLower = headerName.toLowerCase();
        
        // Incluir headers útiles para diagnóstico
        return nameLower.startsWith("x-") || 
               nameLower.equals("content-type") || 
               nameLower.equals("user-agent") ||
               nameLower.equals("accept") ||
               nameLower.equals("origin") ||
               nameLower.equals("referer");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1; // Ejecutar muy temprano en la cadena
    }
}