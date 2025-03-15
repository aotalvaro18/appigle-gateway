package com.appigle.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import java.time.Duration;

/**
 * Configuración de patrones de resiliencia para el API Gateway.
 *
 * Esta clase define configuraciones para Circuit Breaker, y Time Limiter
 * para garantizar la robustez del sistema frente a fallos de servicios downstream.
 * Implementa el patrón Circuit Breaker de Resilience4j con configuraciones
 * específicas para diferentes servicios.
 */
@Configuration
@Profile("azure-recovery")
public class CircuitBreakerConfiguration {
    
    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerConfiguration.class);
    
    /**
     * Configuración por defecto para Circuit Breakers.
     * Aplica a todos los circuit breakers que no tienen configuración específica.
     *
     * Características clave:
     * - Sliding window basada en un número de llamadas (no en tiempo)
     * - Umbral de fallo del 50%
     * - Detección de llamadas lentas
     * - Transición automática de OPEN a HALF_OPEN
     * - Registro de excepciones específicas
     *
     * @return Customizador para la factory de circuit breakers
     */
    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCircuitBreakerFactoryCustomizer() {
        logger.info("Configurando circuit breaker por defecto con timeout: 5s, tasa de fallos: 50%, ventana: 10 llamadas");
        
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                    // Configuración de la ventana deslizante
                    .slidingWindowSize(10)
                    .minimumNumberOfCalls(5) // Mínimo de llamadas para activar el circuit breaker
                    .failureRateThreshold(50) // 50% de fallos para abrir el circuito
                    
                    // Configuración de estados y transiciones
                    .waitDurationInOpenState(Duration.ofSeconds(10)) // Tiempo en estado abierto
                    .permittedNumberOfCallsInHalfOpenState(3) // Llamadas permitidas en medio abierto
                    .automaticTransitionFromOpenToHalfOpenEnabled(true) // Transición automática
                    
                    // Configuración de detección de llamadas lentas
                    .slowCallRateThreshold(50) // 50% de llamadas lentas para abrir el circuito
                    .slowCallDurationThreshold(Duration.ofSeconds(2)) // Umbral para considerar una llamada lenta
                    
                    // Excepciones que cuentan como fallos
                    .recordExceptions(
                        java.io.IOException.class,
                        java.util.concurrent.TimeoutException.class,
                        java.net.ConnectException.class
                    )
                    .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                    .timeoutDuration(Duration.ofSeconds(5)) // Timeout para cada llamada
                    .build())
                .build());
    }
    
    /**
     * Configuración específica para el Circuit Breaker del servicio de autenticación.
     * Utiliza valores más indulgentes por ser un servicio crítico.
     *
     * @return Customizador para el circuit breaker del servicio de autenticación
     */
    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> authServiceCircuitBreakerCustomizer() {
        logger.info("Configurando circuit breaker específico para el servicio de autenticación");
        
        return factory -> factory.configure(builder -> builder
                .circuitBreakerConfig(CircuitBreakerConfig.custom()
                    .slidingWindowSize(20) // Ventana más grande para este servicio crítico
                    .minimumNumberOfCalls(10) // Más llamadas para activar
                    .failureRateThreshold(40) // Umbral más bajo (40%)
                    .waitDurationInOpenState(Duration.ofSeconds(20)) // Más tiempo en estado abierto
                    .permittedNumberOfCallsInHalfOpenState(5) // Más llamadas en estado semi-abierto
                    .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                    .timeoutDuration(Duration.ofSeconds(10)) // Mayor timeout para autenticación
                    .build()), "authServiceCircuitBreaker"); // Nombre específico del circuit breaker
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
        info.append("  - Timeout: 5s\n");
        info.append("  - Sliding Window: 10 llamadas\n");
        info.append("  - Failure Rate Threshold: 50%\n");
        info.append("- Circuit Breaker para Auth Service:\n");
        info.append("  - Timeout: 10s\n");
        info.append("  - Sliding Window: 20 llamadas\n");
        info.append("  - Failure Rate Threshold: 40%\n");
        
        return info.toString();
    }
}