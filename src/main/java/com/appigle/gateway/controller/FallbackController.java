package com.appigle.gateway.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
//cada que se haga push necesita algo   que se haya movido
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controlador para manejar respuestas de fallback cuando los servicios downstream no están disponibles.
 *
 * Este controlador proporciona endpoints alternativos que se activan mediante
 * circuit breakers cuando los servicios originales fallan, permitiendo:
 * - Degradación elegante de la funcionalidad
 * - Respuestas informativas a los clientes
 * - Backoff exponencial para retries
 * - Monitoreo de patrones de fallo
 */
@RestController
@RequestMapping("/fallback")
@Profile("azure-recovery")
public class FallbackController {
    
    private static final Logger logger = LoggerFactory.getLogger(FallbackController.class);
    
    /**
     * Mapa concurrent-safe para llevar la cuenta de activaciones por servicio.
     * Permite implementar estrategias de backoff exponencial.
     */
    private final Map<String, AtomicInteger> fallbackCounters = new ConcurrentHashMap<>();
    
    /**
     * Tiempo por defecto para sugerir retry (en segundos)
     */
    private static final int DEFAULT_RETRY_SECONDS = 30;
    
    /**
     * Fallback específico para el servicio de autenticación.
     * Se activa cuando el circuit breaker detecta problemas en este servicio.
     *
     * @param exchange Intercambio de solicitud/respuesta
     * @return Respuesta estructurada indicando la no disponibilidad del servicio
     */
    @GetMapping(value = "/auth", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> authServiceFallback(ServerWebExchange exchange) {
        String requestPath = exchange.getRequest().getPath().value();
        incrementFallbackCounter("auth");
        
        logger.warn("Fallback activado para servicio de autenticación. Ruta original: {}", requestPath);
        
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", System.currentTimeMillis());
        response.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        response.put("error", "Service Unavailable");
        response.put("code", "AUTH_SERVICE_UNAVAILABLE");
        response.put("message", "El servicio de autenticación no está disponible temporalmente. Por favor, inténtelo de nuevo más tarde.");
        response.put("path", requestPath);
        response.put("retryAfter", calculateRetryAfter("auth"));
        
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(calculateRetryAfter("auth")))
                .body(response);
    }
    
    /**
     * Fallback genérico para cualquier servicio.
     * Permite manejar fallbacks para servicios que se agregarán en el futuro.
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
        
        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", System.currentTimeMillis());
        response.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        response.put("error", "Service Unavailable");
        response.put("code", errorCode);
        response.put("message", errorMessage);
        response.put("path", requestPath);
        response.put("retryAfter", calculateRetryAfter(serviceName));
        
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(calculateRetryAfter(serviceName)))
                .body(response);
    }
    
    /**
     * Incrementa el contador de activaciones de fallback para un servicio.
     * Utiliza computeIfAbsent para manejar el caso de servicios nuevos de forma thread-safe.
     *
     * @param service Nombre del servicio
     */
    private void incrementFallbackCounter(String service) {
        fallbackCounters.computeIfAbsent(service, k -> new AtomicInteger(0)).incrementAndGet();
    }
    
    /**
     * Calcula el tiempo recomendado para reintentar basado en el historial de fallbacks.
     * Implementa un backoff exponencial acotado para evitar sobrecarga de servicios en problemas.
     *
     * @param service Nombre del servicio
     * @return Tiempo en segundos hasta que se pueda reintentar
     */
    private int calculateRetryAfter(String service) {
        AtomicInteger counter = fallbackCounters.get(service);
        
        if (counter == null) {
            return DEFAULT_RETRY_SECONDS;
        }
        
        // Aumentar gradualmente el tiempo de espera según el número de fallbacks recientes
        // Esto implementa un backoff exponencial acotado
        int count = counter.get();
        int baseRetry = DEFAULT_RETRY_SECONDS;
        
        if (count <= 5) {
            return baseRetry;
        } else if (count <= 10) {
            return baseRetry * 2;
        } else if (count <= 20) {
            return baseRetry * 4;
        } else {
            return baseRetry * 8; // Máximo 4 minutos
        }
    }
    
    /**
     * Obtiene estadísticas sobre las activaciones de fallback.
     * Útil para monitoreo y diagnóstico de patrones de fallo.
     *
     * @return Mapa con estadísticas de fallback por servicio
     */
    public Map<String, Integer> getFallbackStatistics() {
        Map<String, Integer> statistics = new HashMap<>();
        fallbackCounters.forEach((service, counter) -> statistics.put(service, counter.get()));
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