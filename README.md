# AppIgle Gateway - Arquitectura Post-Recuperación

## Visión General

El API Gateway AppIgle sirve como punto de entrada único para todas las aplicaciones cliente, gestionando:

- Autenticación y autorización
- Enrutamiento
- Rate limiting
- Circuit breaking
- Observabilidad

## Arquitectura

La arquitectura actual se basa en Spring Cloud Gateway desplegado en Azure Container Apps, eliminando dependencias innecesarias como Eureka Service Discovery y Config Server.

### Componentes Principales

#### 1. Seguridad
- Autenticación basada en JWT
- Validación de tokens en memoria
- Headers seguros

#### 2. Resiliencia
- Circuit breakers configurados por servicio
- Timeouts optimizados
- Mecanismos de fallback

#### 3. Observabilidad
- Integración con Application Insights
- Métricas personalizadas
- Logging estructurado

#### 4. Rate Limiting
- Implementación en memoria
- Límites por IP y por usuario autenticado

### Diagrama de Flujo

Cliente → Azure Container Apps → API Gateway → Microservicios
↑                    ↑
|                    |
Application Insights  ←  Métricas

## Despliegue

El gateway se despliega en Azure Container Apps con configuración de escalado automático:
- Min replicas: 0 (escala a cero cuando no hay tráfico)
- Max replicas: 3
- Trigger: 20 solicitudes concurrentes

## Monitoreo

- Métricas disponibles en Application Insights
- Dashboard disponible en Azure Portal
- Endpoints de salud: `/actuator/health`

## Operaciones Comunes

### Verificar estado
```bash
curl https://[gateway-url]/actuator/health

OJO IMPLEMENTAR BlackList para tokens cuando despleigue Redis en produccion,
por ahora no esta funcionando el BlackList.