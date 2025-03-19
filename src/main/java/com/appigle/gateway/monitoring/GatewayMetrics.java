package com.appigle.gateway.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Centraliza la definición y registro de métricas del Gateway.
 * Proporciona métodos de alto nivel para registrar eventos comunes.
 */
@Component
@Profile("azure")
public class GatewayMetrics {

    private final Counter totalRequestsCounter;
    private final Counter successRequestsCounter;
    private final Counter failedRequestsCounter;
    private final Counter unauthorizedRequestsCounter;
    private final Timer requestsTimer;

    public GatewayMetrics(MeterRegistry registry) {
        this.totalRequestsCounter = Counter.builder("gateway.requests.total")
                .description("Total number of gateway requests")
                .register(registry);
        
        this.successRequestsCounter = Counter.builder("gateway.requests.success")
                .description("Number of successful gateway requests")
                .register(registry);
        
        this.failedRequestsCounter = Counter.builder("gateway.requests.failed")
                .description("Number of failed gateway requests")
                .register(registry);
        
        this.unauthorizedRequestsCounter = Counter.builder("gateway.requests.unauthorized")
                .description("Number of unauthorized gateway requests")
                .register(registry);
        
        this.requestsTimer = Timer.builder("gateway.requests.duration")
                .description("Gateway request duration")
                .register(registry);
    }

    /**
     * Registra una nueva solicitud recibida
     */
    public void recordRequest() {
        totalRequestsCounter.increment();
    }

    /**
     * Registra una solicitud procesada con éxito
     */
    public void recordSuccessfulRequest() {
        successRequestsCounter.increment();
    }

    /**
     * Registra una solicitud fallida
     */
    public void recordFailedRequest() {
        failedRequestsCounter.increment();
    }

    /**
     * Registra una solicitud no autorizada
     */
    public void recordUnauthorizedRequest() {
        unauthorizedRequestsCounter.increment();
    }

    /**
     * Inicia un temporizador para medir la duración de una solicitud
     */
    public Timer.Sample startTimer() {
        return Timer.start();
    }

    /**
     * Detiene un temporizador y registra la duración
     */
    public void stopTimer(Timer.Sample sample) {
        sample.stop(requestsTimer);
    }
}