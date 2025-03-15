package com.appigle.gateway.controller;

import com.appigle.gateway.exception.ErrorResponseBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controlador para manejar respuestas de fallback cuando los servicios downstream no están disponibles.
 * 
 * Este controlador proporciona endpoints alternativos que se activan mediante 
 * circuit breakers cuando los servicios originales fallan, ofreciendo una 
 * degradación elegante de la funcionalidad y respuestas informativas a los clientes.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {
    private static final Logger logger = LoggerFactory.getLogger(FallbackController.class);
    
    // Contador para seguimiento de activaciones de fallback por servicio
    private final Map<String, AtomicInteger> fallbackCounters = new ConcurrentHashMap<>();
    
    private final ErrorResponseBuilder errorResponseBuilder;
    
    @Value("${fallback.default-retry-after:30}")
    private int defaultRetryAfterSeconds;
    
    @Value("${fallback.detailed-errors:true}")
    private boolean detailedErrors;
    
    /**
     * Constructor principal que recibe el builder para respuestas de error.
     * 
     * @param errorResponseBuilder Builder para respuestas de error estructuradas
     */
    public FallbackController(ErrorResponseBuilder errorResponseBuilder) {
        this.errorResponseBuilder = errorResponseBuilder;
    }

    /**
     * Fallback para el servicio de autenticación.
     * 
     * @param exchange Intercambio de solicitud/respuesta
     * @return Respuesta estructurada indicando la no disponibilidad del servicio
     */
    @GetMapping(value = "/auth", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> authServiceFallback(ServerWebExchange exchange) {
        String requestPath = exchange.getRequest().getPath().value();
        incrementFallbackCounter("auth");
        
        logger.warn("Fallback activado para servicio de autenticación. Ruta original: {}", requestPath);
        
        Map<String, Object> response = errorResponseBuilder.buildServiceUnavailableError(
                "AUTH_SERVICE_UNAVAILABLE",
                "Servicio de autenticación no disponible",
                "El servicio de autenticación no está disponible temporalmente. " + 
                "Por favor, inténtelo de nuevo más tarde.",
                requestPath,
                calculateRetryAfter("auth")
        );
        
        if (detailedErrors) {
            addDetailedErrorInfo(response, "auth", exchange);
        }
        
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(calculateRetryAfter("auth")))
                .body(response);
    }
    
    /**
     * Fallback para el servicio de contenido.
     * 
     * @param exchange Intercambio de solicitud/respuesta
     * @return Respuesta estructurada indicando la no disponibilidad del servicio
     */
    @GetMapping(value = "/content", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> contentServiceFallback(ServerWebExchange exchange) {
        String requestPath = exchange.getRequest().getPath().value();
        incrementFallbackCounter("content");
        
        logger.warn("Fallback activado para servicio de contenido. Ruta original: {}", requestPath);
        
        Map<String, Object> response = errorResponseBuilder.buildServiceUnavailableError(
                "CONTENT_SERVICE_UNAVAILABLE",
                "Servicio de contenido no disponible",
                "El servicio de contenido no está disponible temporalmente. " + 
                "Por favor, inténtelo de nuevo más tarde.",
                requestPath,
                calculateRetryAfter("content")
        );
        
        if (detailedErrors) {
            addDetailedErrorInfo(response, "content", exchange);
        }
        
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(calculateRetryAfter("content")))
                .body(response);
    }
    
    /**
     * Fallback para el servicio de eventos.
     * 
     * @param exchange Intercambio de solicitud/respuesta
     * @return Respuesta estructurada indicando la no disponibilidad del servicio
     */
    @GetMapping(value = "/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> eventsServiceFallback(ServerWebExchange exchange) {
        String requestPath = exchange.getRequest().getPath().value();
        incrementFallbackCounter("events");
        
        logger.warn("Fallback activado para servicio de eventos. Ruta original: {}", requestPath);
        
        Map<String, Object> response = errorResponseBuilder.buildServiceUnavailableError(
                "EVENTS_SERVICE_UNAVAILABLE",
                "Servicio de eventos no disponible",
                "El servicio de eventos no está disponible temporalmente. " + 
                "Sus solicitudes se procesarán una vez que el servicio se restablezca.",
                requestPath,
                calculateRetryAfter("events")
        );
        
        if (detailedErrors) {
            addDetailedErrorInfo(response, "events", exchange);
        }
        
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(calculateRetryAfter("events")))
                .body(response);
    }
    
    /**
     * Fallback genérico para cualquier servicio.
     * 
     * @param serviceName Nombre del servicio que falló
     * @param exchange Intercambio de solicitud/respuesta
     * @return Respuesta estructurada indicando la no disponibilidad del servicio
     */
    @GetMapping(value = "/{serviceName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> dynamicServiceFallback(
            @PathVariable String serviceName,
            ServerWebExchange exchange) {
        
        String requestPath = exchange.getRequest().getPath().value();
        incrementFallbackCounter(serviceName);
        
        logger.warn("Fallback activado para servicio: {}. Ruta original: {}", serviceName, requestPath);
        
        String errorCode = serviceName.toUpperCase() + "_SERVICE_UNAVAILABLE";
        String errorTitle = "Servicio " + serviceName + " no disponible";
        String errorMessage = "El servicio solicitado no está disponible temporalmente. " +
                              "Por favor, inténtelo de nuevo más tarde.";
        
        Map<String, Object> response = errorResponseBuilder.buildServiceUnavailableError(
                errorCode,
                errorTitle,
                errorMessage,
                requestPath,
                calculateRetryAfter(serviceName)
        );
        
        if (detailedErrors) {
            addDetailedErrorInfo(response, serviceName, exchange);
        }
        
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(calculateRetryAfter(serviceName)))
                .body(response);
    }

    /**
     * Fallback por defecto para cualquier servicio no especificado.
     * 
     * @param exchange Intercambio de solicitud/respuesta
     * @return Respuesta estructurada indicando la no disponibilidad del servicio
     */
    @GetMapping(value = "/default", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> defaultFallback(ServerWebExchange exchange) {
        String requestPath = exchange.getRequest().getPath().value();
        incrementFallbackCounter("default");
        
        logger.warn("Fallback por defecto activado. Ruta original: {}", requestPath);
        
        Map<String, Object> response = errorResponseBuilder.buildServiceUnavailableError(
                "SERVICE_UNAVAILABLE",
                "Servicio no disponible",
                "El servicio solicitado no está disponible temporalmente. " +
                "Por favor, inténtelo de nuevo más tarde.",
                requestPath,
                defaultRetryAfterSeconds
        );
        
        if (detailedErrors) {
            addDetailedErrorInfo(response, "default", exchange);
        }
        
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(defaultRetryAfterSeconds))
                .body(response);
    }
    
    /**
     * Incrementa el contador de activaciones de fallback para un servicio.
     * 
     * @param service Nombre del servicio
     */
    private void incrementFallbackCounter(String service) {
        fallbackCounters.computeIfAbsent(service, k -> new AtomicInteger(0)).incrementAndGet();
    }
    
    /**
     * Calcula el tiempo recomendado para reintentar basado en el historial de fallbacks.
     * 
     * @param service Nombre del servicio
     * @return Tiempo en segundos hasta que se pueda reintentar
     */
    private int calculateRetryAfter(String service) {
        AtomicInteger counter = fallbackCounters.get(service);
        
        if (counter == null) {
            return defaultRetryAfterSeconds;
        }
        
        // Aumentar gradualmente el tiempo de espera según el número de fallbacks recientes
        // Esto implementa un backoff exponencial acotado
        int count = counter.get();
        int baseRetry = defaultRetryAfterSeconds;
        
        if (count <= 5) {
            return baseRetry;
        } else if (count <= 10) {
            return baseRetry * 2;
        } else if (count <= 20) {
            return baseRetry * 4;
        } else {
            return baseRetry * 8;
        }
    }
    
    /**
     * Agrega información detallada de error a la respuesta.
     * 
     * @param response Mapa de respuesta a enriquecer
     * @param service Nombre del servicio
     * @param exchange Intercambio de solicitud/respuesta
     */
    private void addDetailedErrorInfo(Map<String, Object> response, String service, ServerWebExchange exchange) {
        Map<String, Object> details = new HashMap<>();
        
        details.put("fallbackCount", Optional.ofNullable(fallbackCounters.get(service))
                .map(AtomicInteger::get)
                .orElse(0));
        
        details.put("requestMethod", exchange.getRequest().getMethod().name());
        
        // Extraer información del circuito (si está disponible)
        String circuitState = Optional.ofNullable(exchange.getAttribute("circuitBreakerState"))
                .map(Object::toString)
                .orElse("UNKNOWN");
        details.put("circuitState", circuitState);
        
        response.put("details", details);
    }
    
    /**
     * Obtiene estadísticas sobre las activaciones de fallback.
     * 
     * @return Mapa con estadísticas de fallback por servicio
     */
    public Map<String, Integer> getFallbackStatistics() {
        Map<String, Integer> statistics = new HashMap<>();
        
        fallbackCounters.forEach((service, counter) -> 
            statistics.put(service, counter.get())
        );
        
        return statistics;
    }
    
    /**
     * Reinicia los contadores de fallback (útil para mantenimiento o pruebas).
     */
    public void resetFallbackCounters() {
        fallbackCounters.clear();
        logger.info("Contadores de fallback reiniciados");
    }
}