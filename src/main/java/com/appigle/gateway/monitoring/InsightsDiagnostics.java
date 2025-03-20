package com.appigle.gateway.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.microsoft.applicationinsights.TelemetryClient;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@Component
@EnableScheduling
@ConditionalOnBean(TelemetryClient.class)
public class InsightsDiagnostics {

    private static final Logger logger = LoggerFactory.getLogger(InsightsDiagnostics.class);
    private final TelemetryClient telemetryClient;
    private final Random random = new Random();

    public InsightsDiagnostics(TelemetryClient telemetryClient) {
        this.telemetryClient = telemetryClient;
        logger.info("InsightsDiagnostics inicializado con TelemetryClient");
    }

    @Scheduled(fixedRate = 60000) // Cada minuto
    public void sendDiagnosticTelemetry() {
        try {
            // Propiedades y métricas de prueba
            Map<String, String> properties = new HashMap<>();
            properties.put("source", "diagnostic");
            properties.put("environment", "azure");
            
            Map<String, Double> metrics = new HashMap<>();
            metrics.put("random_value", random.nextDouble() * 100);
            
            // Enviar evento con propiedades y métricas
            telemetryClient.trackEvent("diagnostic_heartbeat", properties, metrics);
            
            // También enviar métricas independientes
            telemetryClient.trackMetric("diagnostic_metric", random.nextDouble() * 100);
            
            logger.debug("Telemetría de diagnóstico enviada a Application Insights");
        } catch (Exception e) {
            logger.error("Error al enviar telemetría de diagnóstico: {}", e.getMessage(), e);
        }
    }
}