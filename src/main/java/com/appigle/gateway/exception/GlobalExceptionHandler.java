package com.appigle.gateway.exception;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.gateway.support.TimeoutException;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
//import java.util.concurrent.TimeoutException;

/**
 * Manejador global de excepciones para API Gateway.
 * 
 * Este componente captura todas las excepciones no manejadas en los filtros
 * y operaciones del gateway, proporcionando respuestas de error consistentes
 * y estructuradas para los clientes, con logging adecuado para diagnóstico.
 */
@Component
@Order(-2) // Alta prioridad, para que se ejecute antes del manejador de errores predeterminado
public class GlobalExceptionHandler implements ErrorWebExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorResponseBuilder errorResponseBuilder;

    /**
     * Constructor que recibe el builder de respuestas de error.
     * 
     * @param errorResponseBuilder Builder para construir respuestas de error estructuradas
     */
    public GlobalExceptionHandler(ErrorResponseBuilder errorResponseBuilder) {
        this.errorResponseBuilder = errorResponseBuilder;
    }

    /**
     * Maneja todas las excepciones no capturadas en la cadena de filtros.
     * 
     * @param exchange Intercambio de la solicitud/respuesta
     * @param ex Excepción a manejar
     * @return Mono completado con la respuesta de error
     */
    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        // Generar un ID único para este error si no existe un trace ID
        String errorId = Optional.ofNullable(MDC.get("trace_id"))
                .orElse(UUID.randomUUID().toString());
                
        // Establecer el ID en el contexto de logging
        MDC.put("error_id", errorId);
        
        // Obtener la ruta de la solicitud para incluirla en la respuesta
        String path = exchange.getRequest().getPath().value();
        
        // Logging detallado del error
        logger.error("Error processing request [{}]: {} - {}", 
                errorId, path, ex.getMessage(), ex);

        // Variables para estado HTTP y contenido de respuesta
        HttpStatusCode statusCode;
        Map<String, Object> errorResponse;

        // Determinar tipo de excepción y construir respuesta adecuada
        if (ex instanceof ResponseStatusException) {
            // Excepciones con código de estado explícito
            statusCode = ((ResponseStatusException) ex).getStatusCode();
            String reasonPhrase = HttpStatus.valueOf(statusCode.value()).getReasonPhrase();
            errorResponse = errorResponseBuilder.buildError(
                    HttpStatus.valueOf(statusCode.value()),
                    statusCode.toString(),
                    reasonPhrase,
                    ex.getMessage() != null ? ex.getMessage() : reasonPhrase,
                    path
            );
        } else if (ex instanceof ExpiredJwtException) {
            // Token JWT expirado
            statusCode = HttpStatus.UNAUTHORIZED;
            errorResponse = errorResponseBuilder.buildTokenExpiredError(path);
            logger.info("Token expirado en ruta: {}", path);
        } else if (ex instanceof MalformedJwtException 
                || ex instanceof UnsupportedJwtException 
                || ex instanceof SignatureException) {
            // Problemas con el token JWT
            statusCode = HttpStatus.UNAUTHORIZED;
            String details = ex.getMessage() != null ? ex.getMessage() : "Token validation failed";
            errorResponse = errorResponseBuilder.buildInvalidTokenError(path, details);
            logger.info("Token inválido en ruta {}: {}", path, details);
        } else if (ex instanceof CallNotPermittedException) {
            // Circuit breaker abierto
            statusCode = HttpStatus.SERVICE_UNAVAILABLE;
            String serviceName = extractServiceNameFromException(ex);
            errorResponse = errorResponseBuilder.buildServiceUnavailableError(
                    "CIRCUIT_OPEN",
                    "Servicio no disponible",
                    "El servicio " + serviceName + " no está disponible temporalmente. Por favor, inténtelo más tarde.",
                    path,
                    30 // Sugerir reintentar en 30 segundos
            );
            logger.warn("Circuit breaker abierto para servicio {}: {}", serviceName, ex.getMessage());
        } else if (ex instanceof RequestNotPermitted) {
            // Rate limit excedido
            statusCode = HttpStatus.TOO_MANY_REQUESTS;
            errorResponse = errorResponseBuilder.buildRateLimitError(path, 60);
            logger.info("Rate limit excedido en ruta: {}", path);
        } else if (ex instanceof TimeoutException || ex instanceof java.util.concurrent.TimeoutException) {
            // Timeout en la respuesta del servicio
            statusCode = HttpStatus.GATEWAY_TIMEOUT;
            errorResponse = errorResponseBuilder.buildError(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "GATEWAY_TIMEOUT",
                    "Tiempo de espera agotado",
                    "El tiempo de espera para la respuesta del servicio se ha agotado. Por favor, inténtelo más tarde.",
                    path
            );
            logger.warn("Timeout para solicitud en ruta: {}", path);
        } else if (ex instanceof NotFoundException) {
            // Servicio no encontrado
            statusCode = HttpStatus.BAD_GATEWAY;
            errorResponse = errorResponseBuilder.buildError(
                    HttpStatus.BAD_GATEWAY,
                    "SERVICE_NOT_FOUND",
                    "Servicio no encontrado",
                    "El servicio requerido no está disponible o no fue encontrado.",
                    path
            );
            logger.warn("Servicio no encontrado para ruta: {}", path);
        } else {
            // Errores internos no manejados específicamente
            statusCode = HttpStatus.INTERNAL_SERVER_ERROR;
            errorResponse = errorResponseBuilder.buildInternalServerError(path, 
                    ex instanceof Exception ? (Exception) ex : new Exception(ex));
            logger.error("Error interno del servidor para ruta {}: {}", path, ex.getMessage());
        }
        
        // Agregar el ID de error a la respuesta para facilitar el diagnóstico
        errorResponse.put("errorId", errorId);

        // Generar el buffer de respuesta con el JSON del error
        byte[] responseBytes = errorResponseBuilder.toJson(errorResponse).getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(responseBytes);
        
        // Configurar código de estado y headers de la respuesta
        exchange.getResponse().setStatusCode(statusCode);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        
        // Agregar headers adicionales para mejorar la experiencia de cliente
        if (statusCode.equals(HttpStatus.TOO_MANY_REQUESTS)) {
            exchange.getResponse().getHeaders().add("Retry-After", "60");
        } else if (statusCode.equals(HttpStatus.SERVICE_UNAVAILABLE)) {
            exchange.getResponse().getHeaders().add("Retry-After", "30");
        }
        
        // Escribir la respuesta
        return exchange.getResponse().writeWith(Mono.just(buffer))
            .doFinally(signalType -> {
                // Limpiar el contexto MDC al finalizar
                MDC.remove("error_id");
            });
    }
    
    /**
     * Extrae el nombre del servicio desde la excepción de circuit breaker.
     * 
     * @param ex Excepción de circuit breaker
     * @return Nombre del servicio o "desconocido" si no se puede determinar
     */
    private String extractServiceNameFromException(Throwable ex) {
        if (ex instanceof CallNotPermittedException) {
            String message = ex.getMessage();
            if (message != null && message.contains("CircuitBreaker '")) {
                int start = message.indexOf("CircuitBreaker '") + "CircuitBreaker '".length();
                int end = message.indexOf("'", start);
                if (end > start) {
                    String breakerName = message.substring(start, end);
                    // Convertir nombres de circuit breaker a nombres de servicio
                    if (breakerName.endsWith("CircuitBreaker")) {
                        return breakerName.substring(0, breakerName.indexOf("CircuitBreaker"));
                    }
                    return breakerName;
                }
            }
        }
        return "desconocido";
    }
}