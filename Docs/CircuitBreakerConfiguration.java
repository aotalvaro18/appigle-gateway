package com.appigle.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración de patrones de resiliencia para el API Gateway.
 * 
 * Esta clase define configuraciones para Circuit Breaker, Retry, y Time Limiter
 * para garantizar la robustez del sistema frente a fallos de servicios downstream.
 * Implementa configuraciones específicas para diferentes servicios y monitoreo
 * de eventos de resiliencia.
 */
@Configuration
public class CircuitBreakerConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerConfiguration.class);

    @Value("${resilience.circuitbreaker.default.timeout:5}")
    private int defaultTimeoutSeconds;
    
    @Value("${resilience.circuitbreaker.default.slidingWindowSize:10}")
    private int defaultSlidingWindowSize;
    
    @Value("${resilience.circuitbreaker.default.failureRateThreshold:50}")
    private float defaultFailureRateThreshold;
    
    @Value("${resilience.retry.default.maxAttempts:3}")
    private int defaultMaxRetryAttempts;
    
    /**
     * Configuración por defecto para Circuit Breakers.
     * Aplica a todos los circuit breakers que no tienen configuración específica.
     * 
     * @return Customizador para la factory de circuit breakers
     */
    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCircuitBreakerFactoryCustomizer() {
        logger.info("Configurando circuit breaker por defecto con timeout: {}s, tasa de fallos: {}%, ventana: {} llamadas",
                defaultTimeoutSeconds, defaultFailureRateThreshold, defaultSlidingWindowSize);
        
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(defaultSlidingWindowSize)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(defaultFailureRateThreshold)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .slowCallRateThreshold(50)
                        .slowCallDurationThreshold(Duration.ofSeconds(2))
                        // Configuraciones adicionales para mejorar resiliencia
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .recordExceptions(
                            java.io.IOException.class,
                            java.util.concurrent.TimeoutException.class,
                            org.springframework.web.reactive.function.client.WebClientResponseException.class,
                            java.net.ConnectException.class
                        )
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(defaultTimeoutSeconds))
                        .cancelRunningFuture(true)
                        .build())
                .build());
    }

    /**
     * Configuración específica para el Circuit Breaker del servicio de autenticación.
     * 
     * @return Customizador para el circuit breaker del servicio de autenticación
     */
    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> authServiceCircuitBreakerCustomizer() {
        logger.info("Configurando circuit breaker específico para el servicio de autenticación");
        
        return factory -> factory.configure(builder -> builder
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(20)
                        .minimumNumberOfCalls(10)
                        .failureRateThreshold(40)
                        .waitDurationInOpenState(Duration.ofSeconds(20))
                        .permittedNumberOfCallsInHalfOpenState(5)
                        // Personalizar excepciones específicas para auth
                        .ignoreExceptions(
                            org.springframework.security.access.AccessDeniedException.class
                        )
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(10))
                        .build()), "authServiceCircuitBreaker");
    }
    
    /**
     * Configuración específica para el Circuit Breaker del servicio de contenido.
     * 
     * @return Customizador para el circuit breaker del servicio de contenido
     */
    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> contentServiceCircuitBreakerCustomizer() {
        logger.info("Configurando circuit breaker específico para el servicio de contenido");
        
        return factory -> factory.configure(builder -> builder
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(15)  // Ventana más grande para tolerar más variabilidad
                        .minimumNumberOfCalls(8)
                        .failureRateThreshold(45)
                        .waitDurationInOpenState(Duration.ofSeconds(15))
                        .permittedNumberOfCallsInHalfOpenState(4)
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(12))  // Más tiempo para operaciones con contenido
                        .build()), "contentServiceCircuitBreaker");
    }
    
    /**
     * Circuit Breaker para APIs públicas, con una configuración más estricta.
     * 
     * @return Customizador para el circuit breaker de APIs públicas
     */
    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> publicApiCircuitBreakerCustomizer() {
        logger.info("Configurando circuit breaker para APIs públicas");
        
        return factory -> factory.configure(builder -> builder
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                        .slidingWindowSize(8)    // Ventana más pequeña para fallar rápido
                        .minimumNumberOfCalls(3)
                        .failureRateThreshold(30) // Umbral más bajo para mayor protección
                        .waitDurationInOpenState(Duration.ofSeconds(30)) // Más tiempo en estado abierto
                        .permittedNumberOfCallsInHalfOpenState(2)
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(3))  // Timeout más corto
                        .build()), "publicApiCircuitBreaker");
    }
    
    /**
     * Configuración para retry patterns con backoff exponencial.
     * Define estrategias de reintento para operaciones fallidas.
     * 
     * @return Configuración de retry
     */
    @Bean
    public RetryConfig retryConfig() {
        return RetryConfig.custom()
                .maxAttempts(defaultMaxRetryAttempts)
                .waitDuration(Duration.ofMillis(100))
                .retryExceptions(
                        java.io.IOException.class,
                        java.util.concurrent.TimeoutException.class,
                        org.springframework.web.reactive.function.client.WebClientResponseException.class
                )
                .ignoreExceptions(
                        org.springframework.security.access.AccessDeniedException.class,
                        java.lang.IllegalArgumentException.class
                )
                .failAfterMaxAttempts(true)
                .build();
    }
    
    /**
     * Registro de eventos de Circuit Breaker para monitoreo y métricas.
     * 
     * @return Consumidor de eventos del registro de Circuit Breaker
     */
    @Bean
    public RegistryEventConsumer<io.github.resilience4j.circuitbreaker.CircuitBreaker> circuitBreakerEventConsumer() {
        return new RegistryEventConsumer<io.github.resilience4j.circuitbreaker.CircuitBreaker>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<io.github.resilience4j.circuitbreaker.CircuitBreaker> event) {
                io.github.resilience4j.circuitbreaker.CircuitBreaker circuitBreaker = event.getAddedEntry();
                logger.info("Circuit Breaker añadido: {}", circuitBreaker.getName());
                
                // Registrar listeners para transiciones de estado
                circuitBreaker.getEventPublisher()
                    .onStateTransition(stateTransition -> {
                        logger.info("Circuit Breaker {} cambió de estado: {} -> {}", 
                            circuitBreaker.getName(), 
                            stateTransition.getStateTransition().getFromState(),
                            stateTransition.getStateTransition().getToState());
                    })
                    .onError(error -> {
                        logger.warn("Circuit Breaker {} registró error: {}", 
                            circuitBreaker.getName(), error.getThrowable().getMessage());
                    });
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<io.github.resilience4j.circuitbreaker.CircuitBreaker> event) {
                logger.info("Circuit Breaker eliminado: {}", event.getRemovedEntry().getName());
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<io.github.resilience4j.circuitbreaker.CircuitBreaker> event) {
                logger.info("Circuit Breaker reemplazado: {}", event.getNewEntry().getName());
            }
        };
    }
    
    /**
     * Configura un registry para Circuit Breakers con nombres y configuraciones personalizadas.
     * 
     * @return Registry configurado para Circuit Breakers
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        // Crear configuraciones para diferentes servicios
        Map<String, CircuitBreakerConfig> configs = new HashMap<>();
        
        // Configuración para Auth Service
        configs.put("auth", CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .failureRateThreshold(40)
                .waitDurationInOpenState(Duration.ofSeconds(20))
                .build());
        
        // Configuración para Content Service
        configs.put("content", CircuitBreakerConfig.custom()
                .slidingWindowSize(15)
                .failureRateThreshold(45)
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .build());
        
        // Configuración para Public API
        configs.put("public", CircuitBreakerConfig.custom()
                .slidingWindowSize(8)
                .failureRateThreshold(30)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
        
        // Crear registry con las configuraciones
        return CircuitBreakerRegistry.of(configs);
    }
    
    /**
     * Integración de métricas para Circuit Breakers.
     * 
     * @param meterRegistry Registro de métricas
     * @param circuitBreakerRegistry Registro de circuit breakers
     * @return Bean de métricas de circuit breaker configurado
     */
    @Bean
    public TaggedCircuitBreakerMetrics circuitBreakerMetrics(
            MeterRegistry meterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        
        TaggedCircuitBreakerMetrics metrics = TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry);
        metrics.bindTo(meterRegistry);
        return metrics;
    }
    
    /**
     * Proporciona información sobre la configuración actual de resiliencia.
     * Útil para diagnóstico y logging.
     * 
     * @return Información de configuración formateada
     */
    public String getResilienceInfo() {
        StringBuilder info = new StringBuilder();
        info.append("Configuración de Resiliencia:\n");
        info.append("- Circuit Breaker por defecto:\n");
        info.append("  - Timeout: ").append(defaultTimeoutSeconds).append("s\n");
        info.append("  - Sliding Window: ").append(defaultSlidingWindowSize).append(" llamadas\n");
        info.append("  - Failure Rate Threshold: ").append(defaultFailureRateThreshold).append("%\n");
        info.append("- Retry por defecto:\n");
        info.append("  - Max Attempts: ").append(defaultMaxRetryAttempts).append("\n");
        
        return info.toString();
    }
}