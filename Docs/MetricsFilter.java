package com.appigle.gateway.filter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

/**
 * Filtro global para recopilar métricas sobre las solicitudes procesadas por el Gateway.
 * 
 * Este filtro registra información sobre tiempos de respuesta, tasas de solicitudes,
 * códigos de estado, y otras métricas importantes para monitoreo y observabilidad.
 * Implementa patrones de alta eficiencia para minimizar el impacto en el rendimiento.
 */
@Component
public class MetricsFilter implements GlobalFilter, Ordered {
    private static final Logger logger = LoggerFactory.getLogger(MetricsFilter.class);
    
    private static final String REQUEST_START_TIME = "metrics.requestStartTime";
    private static final String PATH_SANITIZED = "metrics.pathSanitized";
    
    private static final AtomicLong ACTIVE_REQUESTS = new AtomicLong(0);
    
    private final MeterRegistry meterRegistry;
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    
    @Value("${metrics.path-groups.enabled:true}")
    private boolean enablePathGroups;
    
    @Value("${metrics.include-exact-path:true}")
    private boolean includeExactPath;
    
    @Value("${metrics.enable-size-metrics:true}")
    private boolean enableSizeMetrics;
    
    @Value("${metrics.path-groups.patterns:/api/auth/**=/api/auth,/api/users/**=/api/users,/api/content/**=/api/content}")
    private String pathGroupPatterns;
    
    private List<PathGroup> pathGroups;
    
    /**
     * Constructor que recibe el registro de métricas.
     * 
     * @param meterRegistry Registro para almacenar métricas
     */
    public MetricsFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
    
    /**
     * Inicializa los grupos de rutas y métricas básicas.
     */
    @PostConstruct
    public void init() {
        // Inicializar grupos de rutas
        initializePathGroups();
        
        // Registrar gauge para solicitudes activas
        meterRegistry.gauge("gateway.requests.active", ACTIVE_REQUESTS);
        
        logger.info("Filtro de métricas inicializado con {} grupos de rutas definidos", 
                pathGroups != null ? pathGroups.size() : 0);
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
        // Marcar el tiempo de inicio e incrementar contador de solicitudes activas
        Instant startTime = Instant.now();
        exchange.getAttributes().put(REQUEST_START_TIME, startTime);
        ACTIVE_REQUESTS.incrementAndGet();

        // Preprocesar y almacenar información sobre la ruta
        String path = exchange.getRequest().getPath().value();
        String sanitizedPath = sanitizePath(path);
        exchange.getAttributes().put(PATH_SANITIZED, sanitizedPath);
        
        return chain.filter(exchange)
            .doOnSuccess(v -> recordMetrics(exchange, startTime, false))
            .doOnError(error -> recordMetrics(exchange, startTime, true))
            .doFinally(signalType -> ACTIVE_REQUESTS.decrementAndGet());
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
            String groupPath = mapPathToGroup(path);
            
            // Construir etiquetas base para métricas
            List<Tag> baseTags = new ArrayList<>();
            baseTags.add(Tag.of("method", method));
            baseTags.add(Tag.of("status", status));
            baseTags.add(Tag.of("route", routeId != null ? routeId : "unknown"));
            baseTags.add(Tag.of("error", String.valueOf(isError)));
            
            // Agregar etiqueta de código de estado agrupado (2xx, 3xx, etc.)
            if (statusCode != null) {
                int statusValue = statusCode.value();
                String statusGroup = (statusValue / 100) + "xx";
                baseTags.add(Tag.of("status_group", statusGroup));
            }
            
            // Crear etiquetas para grupo de ruta
            List<Tag> groupTags = new ArrayList<>(baseTags);
            groupTags.add(Tag.of("path_group", groupPath));
            
            // Registrar métricas para el grupo de ruta
            recordTimerMetric("gateway.requests.group", groupTags, duration);
            recordCounterMetric("gateway.requests.count.group", groupTags);
            
            // Si está habilitado, registrar métricas con la ruta exacta
            if (includeExactPath) {
                List<Tag> pathTags = new ArrayList<>(baseTags);
                pathTags.add(Tag.of("path", path));
                
                recordTimerMetric("gateway.requests.path", pathTags, duration);
                recordCounterMetric("gateway.requests.count.path", pathTags);
            }
            
            // Registrar métricas de tamaño si están habilitadas
            if (enableSizeMetrics) {
                recordSizeMetrics(exchange, baseTags);
            }
            
            // Registrar métricas específicas según código de estado
            if (statusCode != null) {
                int statusValue = statusCode.value();
                
                // Contadores separados para tipos de respuesta
                if (statusValue >= 400) {
                    recordCounterMetric("gateway.requests.errors", groupTags);
                }
                if (statusValue >= 500) {
                    recordCounterMetric("gateway.requests.server_errors", groupTags);
                }
                if (statusValue == 429) {
                    recordCounterMetric("gateway.requests.rate_limited", groupTags);
                }
                if (statusValue == 504) {
                    recordCounterMetric("gateway.requests.timeout", groupTags);
                }
            }
            
        } catch (Exception e) {
            // Asegurarse de que los errores en la recopilación de métricas no afecten el flujo principal
            logger.warn("Error registrando métricas: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Registra métricas relacionadas con el tamaño de solicitud/respuesta.
     * 
     * @param exchange Intercambio de solicitud/respuesta
     * @param baseTags Etiquetas base para las métricas
     */
    private void recordSizeMetrics(ServerWebExchange exchange, List<Tag> baseTags) {
        // Tamaño de solicitud
        Long requestSize = exchange.getRequest().getHeaders().getContentLength();
        if (requestSize > 0) {
            DistributionSummary.builder("gateway.request.size")
                    .tags(baseTags)
                    .register(meterRegistry)
                    .record(requestSize);
        }
        
        // Tamaño de respuesta
        Long responseSize = exchange.getResponse().getHeaders().getContentLength();
        if (responseSize > 0) {
            DistributionSummary.builder("gateway.response.size")
                    .tags(baseTags)
                    .register(meterRegistry)
                    .record(responseSize);
        }
    }
    
    /**
     * Mapea una ruta a su grupo configurado.
     * 
     * @param path Ruta a mapear
     * @return Grupo al que pertenece la ruta o la ruta original si no hay coincidencia
     */
    private String mapPathToGroup(String path) {
        if (!enablePathGroups || pathGroups == null) {
            return path;
        }
        
        for (PathGroup group : pathGroups) {
            if (group.matches(path)) {
                return group.getGroupName();
            }
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
     * Inicializa los grupos de rutas a partir de la configuración.
     */
    private void initializePathGroups() {
        if (!enablePathGroups || pathGroupPatterns == null || pathGroupPatterns.isBlank()) {
            pathGroups = List.of();
            return;
        }
        
        String[] patterns = pathGroupPatterns.split(",");
        List<PathGroup> groups = new ArrayList<>();
        
        for (String pattern : patterns) {
            String[] parts = pattern.split("=");
            if (parts.length == 2) {
                String pathPattern = parts[0].trim();
                String groupName = parts[1].trim();
                groups.add(new PathGroup(pathPattern, groupName));
            }
        }
        
        this.pathGroups = groups;
        logger.info("Configurados {} grupos de rutas para métricas", groups.size());
    }
    
    /**
     * Obtiene el ID de la ruta a partir del intercambio.
     * 
     * @param exchange Intercambio de solicitud/respuesta
     * @return ID de la ruta o null si no existe
     */
    private String getRouteId(ServerWebExchange exchange) {
        return Optional.ofNullable(exchange.getAttribute(GATEWAY_ROUTE_ATTR))
                .map(route -> ((Route) route).getId())
                .orElse("direct");
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
     * 
     * @return Orden de ejecución (valor más bajo = mayor prioridad)
     */
    @Override
    public int getOrder() {
        return -130; // Ejecutar antes de otros filtros para registrar el tiempo total
    }
    
    /**
     * Clase interna para representar un grupo de rutas.
     */
    private static class PathGroup {
        private final String pattern;
        private final String groupName;
        private final Predicate<String> matcher;
        
        public PathGroup(String pattern, String groupName) {
            this.pattern = pattern;
            this.groupName = groupName;
            
            // Crear un matcher basado en el patrón
            if (pattern.endsWith("/**")) {
                String prefix = pattern.substring(0, pattern.length() - 2);
                this.matcher = path -> path.startsWith(prefix);
            } else if (pattern.endsWith("/*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                this.matcher = path -> path.startsWith(prefix) && !path.substring(prefix.length()).contains("/");
            } else {
                this.matcher = path -> path.equals(pattern);
            }
        }
        
        public boolean matches(String path) {
            return matcher.test(path);
        }
        
        public String getGroupName() {
            return groupName;
        }
    }
}