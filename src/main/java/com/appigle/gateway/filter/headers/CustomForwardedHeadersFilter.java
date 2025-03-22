package com.appigle.gateway.filter.headers;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Filtro personalizado para manejar encabezados forwarded.
 * Reemplaza el ForwardedHeadersFilter estándar que tiene problemas de compatibilidad
 * con la versión actual de Spring HTTP.
 */

public class CustomForwardedHeadersFilter implements HttpHeadersFilter, GatewayFilter, Ordered {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String X_FORWARDED_HOST = "X-Forwarded-Host";
    private static final String X_FORWARDED_PORT = "X-Forwarded-Port";
    private static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";
    private static final String X_FORWARDED_PREFIX = "X-Forwarded-Prefix";

    @Override
    public HttpHeaders filter(HttpHeaders headers, ServerWebExchange exchange) {
        HttpHeaders filtered = new HttpHeaders();
        
        // Copiar todos los encabezados originales al nuevo objeto HttpHeaders
        headers.forEach((key, values) -> filtered.addAll(key, values));
        
        // Manejar especialmente los encabezados X-Forwarded-*
        String remoteAddr = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        
        // X-Forwarded-For
        List<String> forwardedFor = filtered.get(X_FORWARDED_FOR);
        if (forwardedFor == null) {
            forwardedFor = new ArrayList<>();
        } else {
            forwardedFor = new ArrayList<>(forwardedFor);
        }
        forwardedFor.add(remoteAddr);
        filtered.put(X_FORWARDED_FOR, forwardedFor);
        
        // Otros encabezados X-Forwarded-* según sea necesario
        if (!filtered.containsKey(X_FORWARDED_HOST)) {
            String host = exchange.getRequest().getURI().getHost();
            if (host != null) {
                filtered.add(X_FORWARDED_HOST, host);
            }
        }
        
        if (!filtered.containsKey(X_FORWARDED_PROTO)) {
            String scheme = exchange.getRequest().getURI().getScheme();
            if (scheme != null) {
                filtered.add(X_FORWARDED_PROTO, scheme);
            }
        }
        
        return filtered;
    }

    @Override
    public boolean supports(Type type) {
        return Type.REQUEST.equals(type);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Implementación del método filter para GatewayFilter
        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(exchange.getRequest().mutate()
                        .headers(headers -> {
                            HttpHeaders filtered = filter(headers, exchange);
                            headers.clear();
                            headers.putAll(filtered);
                        })
                        .build())
                .build();
        
        return chain.filter(mutatedExchange);
    }
}