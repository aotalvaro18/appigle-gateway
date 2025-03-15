package com.appigle.gateway.filter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * Filtro global para recopilar métricas sobre las solicitudes procesadas por el Gateway.
 *
 * Este filtro registra información detallada sobre:
 * - Tiempos de respuesta (histogramas)
 * - Tasas de solicitudes (counters)
 * - Códigos de estado por ruta y método
 * - Errores por tipo
 * 
 * Está diseñado para ser eficiente y aportar información valiosa para el monitoreo
 * de la aplicación en entornos de producción.
 */
@Component
@Profile("azure-recovery")
public class MetricsFilter implements GlobalFilter, Ordered {
    private static final Logger logger = LoggerFactory.getLogger(MetricsFilter.class);

    private static final String REQUEST_START_TIME = "metrics.requestStartTime";
    private static final String PATH_SANITIZED = "metrics.pathSanitized";

    // Cachés para evitar crear métricas constantemente
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    
    private final MeterRegistry meterRegistry;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    
    // Grupos de rutas predefinidos para evitar explosión de cardinalidad en métricas
    private final Map<String, String> pathGroups = Map.of(
        "/api/auth/**", "/api/auth",
        "/api/users/**", "/api/users",
        "/api/content/**", "/api/content",
        "/api/email-verification/**", "/api/email-verification",
        "/actuator/**", "/actuator"
    );

    /**
     * Constructor que recibe el registro de métricas.
     *
     * @param meterRegistry Registro para almacenar métricas
     */
    public MetricsFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        logger.info("Filtro de métricas inicializado");
    }

    /**
     * Método principal del filtro que procesa cada solicitud.
     * Registra el inicio de la solicitud, procesa la ruta y aplica las métricas correspondientes.
     *
     * @param exchange Intercambio de solicitud/respuesta
     * @param chain Cadena de filtros
     * @return Mono que se completa cuando el filtro ha terminado su trabajo
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Marcar el tiempo de inicio
        Instant startTime = Instant.now();
        exchange.getAttributes().put(REQUEST_START_TIME, startTime);

        // Preprocesar y almacenar información sobre la ruta
        String path = exchange.getRequest().getPath().value();
        String sanitizedPath = sanitizePath(path);
        String groupPath = mapPathToGroup(sanitizedPath);
        exchange.getAttributes().put(PATH_SANITIZED, groupPath);
        
        // Incrementar contador de solicitudes
        recordCounterMetric("gateway.requests.count", 
            List.of(Tag.of("path_group", groupPath), 
                   Tag.of("method", exchange.getRequest().getMethod().name())));

        return chain.filter(exchange)
            .doOnSuccess(v -> recordMetrics(exchange, startTime, false))
            .doOnError(error -> recordMetrics(exchange, startTime, true));
    }

    /**
     * Registra las métricas para una solicitud completada.
     *
     * @param exchange Intercambio de solicitud/respuesta
     * @param startTime Tiempo de inicio de la solicitud
     * @param isError Indica si la solicitud terminó con error
     */
    private void recordMetrics(ServerWebExchange exchange, Instant startTime, boolean isError) {
        try {
            // Calcular duración
            Duration duration = Duration.between(startTime, Instant.now());
            
            // Extraer información básica
            HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
            String status = statusCode != null ? String.valueOf(statusCode.value()) : "unknown";
            String method = exchange.getRequest().getMethod().name();
            String routeId = getRouteId(exchange);
            
            // Determinar el grupo de la ruta
            String path = (String) exchange.getAttributes().get(PATH_SANITIZED);
            
            // Construir etiquetas base para métricas
            List<Tag> tags = new ArrayList<>();
            tags.add(Tag.of("method", method));
            tags.add(Tag.of("status", status));
            tags.add(Tag.of("route", routeId != null ? routeId : "unknown"));
            tags.add(Tag.of("error", String.valueOf(isError)));
            tags.add(Tag.of("path_group", path));
            
            // Agregar etiqueta de código de estado agrupado (2xx, 3xx, etc.)
            if (statusCode != null) {
                int statusValue = statusCode.value();
                String statusGroup = (statusValue / 100) + "xx";
                tags.add(Tag.of("status_group", statusGroup));
            }
            
            // Registrar tiempo de respuesta
            recordTimerMetric("gateway.requests.duration", tags, duration);
            
            // Registrar métricas específicas según código de estado
            if (statusCode != null) {
                int statusValue = statusCode.value();
                
                // Contadores separados para tipos de respuesta
                if (statusValue >= 400) {
                    recordCounterMetric("gateway.requests.errors", tags);
                }
                if (statusValue >= 500) {
                    recordCounterMetric("gateway.requests.server_errors", tags);
                }
                if (statusValue == 429) {
                    recordCounterMetric("gateway.requests.rate_limited", tags);
                }
                if (statusValue == 504) {
                    recordCounterMetric("gateway.requests.timeout", tags);
                }
            }
            
            // Log para depuración con información detallada
            if (logger.isDebugEnabled()) {
                logger.debug("Request metric: method={}, path={}, status={}, duration={}ms", 
                    method, path, status, duration.toMillis());
            }
        } catch (Exception e) {
            // Asegurarse de que los errores en la recopilación de métricas no afecten el flujo principal
            logger.warn("Error registrando métricas: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Mapea una ruta a su grupo configurado para evitar cardinalidad excesiva.
     *
     * @param path Ruta a mapear
     * @return Grupo al que pertenece la ruta o la ruta original si no hay coincidencia
     */
    private String mapPathToGroup(String path) {
        for (Map.Entry<String, String> entry : pathGroups.entrySet()) {
            if (pathMatcher.match(entry.getKey(), path)) {
                return entry.getValue();
            }
        }
        
        // Si no coincide con ningún grupo, usar los primeros dos segmentos de la ruta
        String[] segments = path.split("/");
        if (segments.length > 2) {
            return "/" + segments[1] + (segments.length > 2 ? "/" + segments[2] : "");
        }
        
        return path;
    }

    /**
     * Sanitiza una ruta para evitar explosión de cardinalidad en métricas.
     * Reemplaza IDs y valores variables con marcadores.
     *
     * @param path Ruta original
     * @return Ruta sanitizada
     */
    private String sanitizePath(String path) {
        if (path == null) {
            return "unknown";
        }
        
        // Reemplazar UUIDs, IDs numéricos y otros patrones variables
        return path.replaceAll("/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "/{uuid}")
                  .replaceAll("/[0-9]+", "/{id}")
                  .replaceAll("=[^/&?]+", "={value}");
    }

    /**
     * Obtiene el ID de la ruta a partir del intercambio.
     *
     * @param exchange Intercambio de solicitud/respuesta
     * @return ID de la ruta o null si no existe
     */
    private String getRouteId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        return route != null ? route.getId() : "direct";
    }

    /**
     * Registra una métrica de temporizador, usando caché para evitar creación excesiva.
     *
     * @param name Nombre de la métrica
     * @param tags Etiquetas
     * @param duration Duración a registrar
     */
    private void recordTimerMetric(String name, List<Tag> tags, Duration duration) {
        String key = createMetricKey(name, tags);
        Timer timer = timers.computeIfAbsent(key, k -> Timer.builder(name)
                .tags(tags)
                .publishPercentileHistogram()
                .register(meterRegistry));
        
        timer.record(duration);
    }

    /**
     * Registra una métrica de contador, usando caché para evitar creación excesiva.
     *
     * @param name Nombre de la métrica
     * @param tags Etiquetas
     */
    private void recordCounterMetric(String name, List<Tag> tags) {
        String key = createMetricKey(name, tags);
        Counter counter = counters.computeIfAbsent(key, k -> Counter.builder(name)
                .tags(tags)
                .register(meterRegistry));
        
        counter.increment();
    }

    /**
     * Crea una clave única para una métrica basada en su nombre y etiquetas.
     * Esto es crucial para la eficiencia del caché interno.
     *
     * @param name Nombre de la métrica
     * @param tags Etiquetas
     * @return Clave única
     */
    private String createMetricKey(String name, List<Tag> tags) {
        StringBuilder key = new StringBuilder(name);
        
        for (Tag tag : tags) {
            key.append(':').append(tag.getKey()).append('=').append(tag.getValue());
        }
        
        return key.toString();
    }

    /**
     * Define el orden de ejecución del filtro en la cadena de filtros.
     * Se ejecuta antes de otros filtros para capturar el tiempo total.
     *
     * @return Orden de ejecución (valor más bajo = mayor prioridad)
     */
    @Override
    public int getOrder() {
        return -130; // Ejecutar antes de otros filtros para registrar el tiempo total
    }
}