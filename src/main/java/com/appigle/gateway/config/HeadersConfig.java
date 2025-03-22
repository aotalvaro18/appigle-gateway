package com.appigle.gateway.config;

import com.appigle.gateway.filter.headers.CustomForwardedHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuración para reemplazar el filtro de encabezados por defecto
 * con nuestra implementación personalizada que es compatible con
 * las versiones actuales de Spring HTTP.
 */
@Configuration
public class HeadersConfig {

    /**
     * Registra nuestro filtro de encabezados personalizado como bean principal
     * para reemplazar el ForwardedHeadersFilter estándar.
     */
    @Bean
    @Primary
    public HttpHeadersFilter customGatewayForwardedHeadersFilter() {
        // Creamos una nueva instancia del filtro personalizado
        return new CustomForwardedHeadersFilter();
    }
}