package com.appigle.gateway.config;

import com.microsoft.applicationinsights.TelemetryClient;
import com.microsoft.applicationinsights.TelemetryConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class ApplicationInsightsConfig {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationInsightsConfig.class);

    @Value("${spring.application.name:appigle-gateway}")
    private String applicationName;

    @Value("${azure.application-insights.instrumentation-key:}")
    private String instrumentationKey;

    /**
     * Configura el TelemetryClient de Application Insights
     */
    @Bean
    @Primary
    @ConditionalOnProperty(value = "azure.application-insights.enabled", havingValue = "true", matchIfMissing = true)
    public TelemetryClient telemetryClient() {
        TelemetryConfiguration configuration = TelemetryConfiguration.getActive();
        
        if (instrumentationKey != null && !instrumentationKey.isEmpty()) {
            configuration.setInstrumentationKey(instrumentationKey);
            logger.info("Application Insights configurado con clave de instrumentación");
            
            // Enviar evento de prueba para verificar la conexión
            TelemetryClient client = new TelemetryClient(configuration);
            client.trackEvent("application_startup");
            client.trackMetric("application_startup_metric", 1.0);
            
            logger.info("Evento de prueba enviado a Application Insights");
            
            return client;
        } else {
            logger.warn("No se ha configurado una clave de instrumentación válida para Application Insights");
            return new TelemetryClient();
        }
    }
}