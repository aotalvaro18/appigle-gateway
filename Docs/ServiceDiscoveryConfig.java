package com.appigle.gateway.config;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.gateway.discovery.DiscoveryClientRouteDefinitionLocator;
import org.springframework.cloud.gateway.discovery.DiscoveryLocatorProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.cloud.loadbalancer.core.RandomLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ReactorLoadBalancer;
import org.springframework.cloud.loadbalancer.core.RoundRobinLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Configuración para el descubrimiento de servicios y balanceo de carga.
 * 
 * Esta clase proporciona beans y configuraciones necesarias para:
 * 1. Localización automática de servicios mediante service discovery
 * 2. Enrutamiento dinámico basado en servicios registrados
 * 3. Estrategias de balanceo de carga
 * 4. Cliente HTTP para comunicación entre servicios
 */
@Configuration
@LoadBalancerClients({
    @LoadBalancerClient(value = "default", configuration = ServiceDiscoveryConfig.class)
})
public class ServiceDiscoveryConfig {
    private static final Logger logger = LoggerFactory.getLogger(ServiceDiscoveryConfig.class);

    @Value("${spring.application.name:appigle-gateway}")
    private String applicationName;

    @Value("${service-discovery.route-id-prefix:}")
    private String routeIdPrefix;
    
    @Value("${service-discovery.lower-case-service-id:true}")
    private boolean lowerCaseServiceId;
    
    private final Environment environment;

    public ServiceDiscoveryConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * Configura un localizador de definiciones de rutas basado en discovery client.
     * 
     * Permite el enrutamiento dinámico a servicios registrados en el service registry,
     * traduciendo automáticamente los nombres de servicio en rutas.
     * 
     * @param reactiveDiscoveryClient Cliente reactivo para service discovery
     * @param discoveryLocatorProperties Propiedades de localización para discovery
     * @return Localizador de definiciones de rutas configurado
     */
    @Bean
    @ConditionalOnProperty(name = "spring.cloud.gateway.discovery.locator.enabled", havingValue = "true", matchIfMissing = true)
    public DiscoveryClientRouteDefinitionLocator discoveryClientRouteDefinitionLocator(
        ReactiveDiscoveryClient reactiveDiscoveryClient,
        DiscoveryLocatorProperties discoveryLocatorProperties) {
        
        // Configurar propiedades del locator según configuración de la aplicación
        if (StringUtils.hasText(routeIdPrefix)) {
            discoveryLocatorProperties.setRouteIdPrefix(routeIdPrefix);
            logger.info("Service Discovery configurado con prefijo de ruta: {}", routeIdPrefix);
        }
        
        discoveryLocatorProperties.setLowerCaseServiceId(lowerCaseServiceId);
        
        // Configuración de predicados personalizados para rutas basadas en discovery
        List<PredicateDefinition> predicates = new ArrayList<>(discoveryLocatorProperties.getPredicates());
        boolean hasPathPredicate = predicates.stream()
                .anyMatch(p -> "Path".equals(p.getName()));
        
        if (!hasPathPredicate) {
            PredicateDefinition pathPredicate = new PredicateDefinition();
            pathPredicate.setName("Path");
            pathPredicate.addArg("pattern", "/api/${serviceId.toLowerCase()}/**");
            predicates.add(pathPredicate);
            discoveryLocatorProperties.setPredicates(predicates);
        }
        
        // Filtros adicionales para rutas basadas en discovery
        List<FilterDefinition> filters = new ArrayList<>(discoveryLocatorProperties.getFilters());
        
        // Verificar y agregar filtro RewritePath si no existe
        boolean hasRewritePathFilter = filters.stream()
                .anyMatch(f -> "RewritePath".equals(f.getName()));
        
        if (!hasRewritePathFilter) {
            FilterDefinition rewritePathFilter = new FilterDefinition();
            rewritePathFilter.setName("RewritePath");
            rewritePathFilter.addArg("regexp", "/api/${serviceId.toLowerCase()}/(?<remaining>.*)");
            rewritePathFilter.addArg("replacement", "/${remaining}");
            filters.add(rewritePathFilter);
        }
        
        // Verificar y agregar filtro AddRequestHeader si no existe
        boolean hasAddRequestHeaderFilter = filters.stream()
                .anyMatch(f -> "AddRequestHeader".equals(f.getName()) && 
                              f.getArgs().containsKey("X-Original-Service"));
        
        if (!hasAddRequestHeaderFilter) {
            FilterDefinition addHeaderFilter = new FilterDefinition();
            addHeaderFilter.setName("AddRequestHeader");
            addHeaderFilter.addArg("name", "X-Original-Service");
            addHeaderFilter.addArg("value", "${serviceId}");
            filters.add(addHeaderFilter);
        }
        
        discoveryLocatorProperties.setFilters(filters);
        
        logger.info("Discovery client route locator configurado con {} servicios disponibles", 
                reactiveDiscoveryClient.getClass().getSimpleName());
        
        return new DiscoveryClientRouteDefinitionLocator(reactiveDiscoveryClient, discoveryLocatorProperties);
    }
    
    /**
     * Configura Web Client con soporte para balanceo de carga.
     * 
     * Este cliente se utiliza para comunicación entre servicios y utiliza
     * el balanceador de carga para distribuir las solicitudes.
     *
     * @return WebClient.Builder con balanceo de carga configurado
     */
    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder()
            .defaultHeader("X-Gateway-Source", applicationName);
    }
    
    /**
     * Configura la estrategia de balanceo de carga Round Robin.
     * Esta es la estrategia por defecto que distribuye las solicitudes
     * equitativamente entre todas las instancias disponibles.
     * 
     * @param factory Factory para clientes de balanceo de carga
     * @return Balanceador Round Robin
     */
    @Bean
    @Primary
    @Profile("!random-lb")
    public ReactorLoadBalancer<ServiceInstance> roundRobinLoadBalancer(
            LoadBalancerClientFactory factory) {
        
        logger.info("Configurando estrategia de balanceo de carga: Round Robin");
        
        // Nombre del servicio por defecto
        String serviceId = environment.getProperty("loadbalancer.client.name", "default");
        
        return new RoundRobinLoadBalancer(
                factory.getLazyProvider(serviceId, ServiceInstanceListSupplier.class),
                serviceId);
    }

    /**
     * Configura la estrategia de balanceo de carga aleatoria.
     * Esta estrategia selecciona instancias al azar para cada solicitud.
     * Se activa cuando el perfil "random-lb" está activo.
     * 
     * @param factory Factory para clientes de balanceo de carga
     * @return Balanceador aleatorio
     */
    @Bean
    @Profile("random-lb")
    public ReactorLoadBalancer<ServiceInstance> randomLoadBalancer(
            LoadBalancerClientFactory factory) {
        
        logger.info("Configurando estrategia de balanceo de carga: Random");
        
        // Nombre del servicio por defecto
        String serviceId = environment.getProperty("loadbalancer.client.name", "default");
        
        return new RandomLoadBalancer(
                factory.getLazyProvider(serviceId, ServiceInstanceListSupplier.class),
                serviceId);
    }
    
    /**
     * Proporciona información sobre la configuración actual del service discovery.
     * Útil para diagnóstico y logging.
     * 
     * @return Información de configuración formateada
     */
    public String getDiscoveryInfo() {
        StringBuilder info = new StringBuilder();
        info.append("Configuración de Service Discovery:\n");
        info.append("- Perfil activo: ").append(String.join(", ", environment.getActiveProfiles())).append("\n");
        info.append("- Estrategia de balanceo: ")
            .append(environment.matchesProfiles("random-lb") ? "Aleatoria" : "Round Robin").append("\n");
        info.append("- Discovery locator habilitado: ")
            .append(environment.getProperty("spring.cloud.gateway.discovery.locator.enabled", "true")).append("\n");
        info.append("- Prefijo de ruta: ").append(routeIdPrefix).append("\n");
        info.append("- Service ID en minúsculas: ").append(lowerCaseServiceId);
        
        return info.toString();
    }
}