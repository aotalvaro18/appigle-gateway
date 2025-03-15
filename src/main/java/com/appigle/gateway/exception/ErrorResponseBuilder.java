package com.appigle.gateway.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Componente para construir respuestas de error estandarizadas.
 * 
 * Proporciona métodos para generar respuestas de error consistentes
 * en formato JSON con información detallada y estructurada.
 * Permite crear diferentes tipos de respuestas de error según
 * el escenario y situación específica.
 */
@Component
public class ErrorResponseBuilder {
    private static final Logger logger = LoggerFactory.getLogger(ErrorResponseBuilder.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;
    
    private final ObjectMapper objectMapper;
    
    @Value("${error.response.include-stacktrace:false}")
    private boolean includeStackTrace;
    
    @Value("${error.response.include-trace-id:true}")
    private boolean includeTraceId;
    
    @Value("${error.response.include-support-contact:true}")
    private boolean includeSupportContact;
    
    @Value("${error.response.support-contact:support@appigle.com}")
    private String supportContact;

    /**
     * Constructor que recibe el ObjectMapper para serialización a JSON.
     * 
     * @param objectMapper Mapper para serialización/deserialización JSON
     */
    public ErrorResponseBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    //Para tercer intento de deploy con git
    /**
     * Construye una respuesta de error básica con los campos estándar.
     * 
     * @param code Código de error específico de la aplicación
     * @param error Descripción breve del error
     * @param message Mensaje detallado para el usuario
     * @param path Ruta de la solicitud que generó el error
     * @return Mapa con la estructura de la respuesta de error
     */
    public Map<String, Object> buildError(String code, String error, String message, String path) {
        Map<String, Object> errorResponse = createBaseErrorResponse(code, error, message, path);
        errorResponse.put("status", HttpStatus.UNAUTHORIZED.value());
        return errorResponse;
    }
    
    /**
     * Construye una respuesta de error con código de estado personalizado.
     * 
     * @param status Código de estado HTTP
     * @param code Código de error específico de la aplicación
     * @param error Descripción breve del error
     * @param message Mensaje detallado para el usuario
     * @param path Ruta de la solicitud que generó el error
     * @return Mapa con la estructura de la respuesta de error
     */
    public Map<String, Object> buildError(HttpStatus status, String code, String error, String message, String path) {
        Map<String, Object> errorResponse = createBaseErrorResponse(code, error, message, path);
        errorResponse.put("status", status.value());
        return errorResponse;
    }
    
    /**
     * Construye una respuesta de error para servicio no disponible con tiempo de reintento.
     * 
     * @param code Código de error específico de la aplicación
     * @param error Descripción breve del error
     * @param message Mensaje detallado para el usuario
     * @param path Ruta de la solicitud que generó el error
     * @return Mapa con la estructura de la respuesta de error
     */
    public Map<String, Object> buildServiceUnavailableError(String code, String error, String message, String path) {
        Map<String, Object> errorResponse = createBaseErrorResponse(code, error, message, path);
        errorResponse.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        errorResponse.put("retryAfter", 30); // Sugerir reintentar en 30 segundos
        return errorResponse;
    }
    
    /**
     * Construye una respuesta de error para servicio no disponible con tiempo de reintento personalizado.
     * 
     * @param code Código de error específico de la aplicación
     * @param error Descripción breve del error
     * @param message Mensaje detallado para el usuario
     * @param path Ruta de la solicitud que generó el error
     * @param retryAfterSeconds Tiempo sugerido para reintentar en segundos
     * @return Mapa con la estructura de la respuesta de error
     */
    public Map<String, Object> buildServiceUnavailableError(String code, String error, String message, String path, int retryAfterSeconds) {
        Map<String, Object> errorResponse = createBaseErrorResponse(code, error, message, path);
        errorResponse.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        errorResponse.put("retryAfter", retryAfterSeconds);
        return errorResponse;
    }
    
    /**
     * Construye una respuesta de error para validación fallida con detalles de los campos inválidos.
     * 
     * @param code Código de error específico de la aplicación
     * @param message Mensaje general de validación
     * @param path Ruta de la solicitud que generó el error
     * @param validationErrors Mapa de errores de validación (campo -> mensaje)
     * @return Mapa con la estructura de la respuesta de error
     */
    public Map<String, Object> buildValidationError(String code, String message, String path, Map<String, String> validationErrors) {
        Map<String, Object> errorResponse = createBaseErrorResponse(code, "Validation Failed", message, path);
        errorResponse.put("status", HttpStatus.BAD_REQUEST.value());
        errorResponse.put("validationErrors", validationErrors);
        return errorResponse;
    }
    
    /**
     * Construye una respuesta de error para autenticación fallida.
     * 
     * @param code Código de error específico de la aplicación
     * @param message Mensaje detallado del fallo de autenticación
     * @param path Ruta de la solicitud que generó el error
     * @return Mapa con la estructura de la respuesta de error
     */
    public Map<String, Object> buildAuthenticationError(String code, String message, String path) {
        Map<String, Object> errorResponse = createBaseErrorResponse(code, "Authentication Failed", message, path);
        errorResponse.put("status", HttpStatus.UNAUTHORIZED.value());
        return errorResponse;
    }
    
    /**
     * Construye una respuesta de error para autorización fallida.
     * 
     * @param code Código de error específico de la aplicación
     * @param message Mensaje detallado del fallo de autorización
     * @param path Ruta de la solicitud que generó el error
     * @return Mapa con la estructura de la respuesta de error
     */
    public Map<String, Object> buildAuthorizationError(String code, String message, String path) {
        Map<String, Object> errorResponse = createBaseErrorResponse(code, "Access Denied", message, path);
        errorResponse.put("status", HttpStatus.FORBIDDEN.value());
        return errorResponse;
    }
    
    /**
     * Construye una respuesta de error para token expirado.
     * 
     * @param path Ruta de la solicitud que generó el error
     * @return Mapa con la estructura de la respuesta de error
     */
    public Map<String, Object> buildTokenExpiredError(String path) {
        Map<String, Object> errorResponse = createBaseErrorResponse(
                "TOKEN_EXPIRED", 
                "Token Expired", 
                "La sesión ha expirado. Por favor, inicie sesión nuevamente.", 
                path);
        errorResponse.put("status", HttpStatus.UNAUTHORIZED.value());
        return errorResponse;
    }
    
    /**
     * Construye una respuesta de error para token inválido.
     * 
     * @param path Ruta de la solicitud que generó el error
     * @param details Detalles adicionales sobre el problema con el token
     * @return Mapa con la estructura de la respuesta de error
     */
    public Map<String, Object> buildInvalidTokenError(String path, String details) {
        Map<String, Object> errorResponse = createBaseErrorResponse(
                "INVALID_TOKEN", 
                "Invalid Token", 
                "El token de autenticación es inválido o está mal formado.", 
                path);
        errorResponse.put("status", HttpStatus.UNAUTHORIZED.value());
        errorResponse.put("details", details);
        return errorResponse;
    }
    
    /**
     * Construye una respuesta de error para solicitud con rate limit excedido.
     * 
     * @param path Ruta de la solicitud que generó el error
     * @param retryAfterSeconds Tiempo sugerido para reintentar en segundos
     * @return Mapa con la estructura de la respuesta de error
     */
    public Map<String, Object> buildRateLimitError(String path, int retryAfterSeconds) {
        Map<String, Object> errorResponse = createBaseErrorResponse(
                "RATE_LIMIT_EXCEEDED", 
                "Too Many Requests", 
                "Ha excedido el límite de solicitudes. Por favor, inténtelo más tarde.", 
                path);
        errorResponse.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        errorResponse.put("retryAfter", retryAfterSeconds);
        return errorResponse;
    }
    
    /**
     * Construye una respuesta de error para error interno del servidor.
     * 
     * @param path Ruta de la solicitud que generó el error
     * @param exception Excepción que causó el error (opcional)
     * @return Mapa con la estructura de la respuesta de error
     */
    public Map<String, Object> buildInternalServerError(String path, Exception exception) {
        Map<String, Object> errorResponse = createBaseErrorResponse(
                "INTERNAL_SERVER_ERROR", 
                "Internal Server Error", 
                "Ha ocurrido un error interno. Por favor, inténtelo más tarde.", 
                path);
        errorResponse.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        
        // Incluir stacktrace solo si está configurado y hay una excepción
        if (includeStackTrace && exception != null) {
            errorResponse.put("exception", exception.getClass().getName());
            errorResponse.put("stacktrace", getStackTraceAsString(exception));
        }
        
        return errorResponse;
    }

    /**
     * Serializa un mapa de respuesta de error a formato JSON.
     * 
     * @param errorResponse Mapa con la estructura de la respuesta de error
     * @return String con la representación JSON de la respuesta
     */
    public String toJson(Map<String, Object> errorResponse) {
        try {
            return objectMapper.writeValueAsString(errorResponse);
        } catch (JsonProcessingException e) {
            logger.error("Error serializando respuesta de error a JSON", e);
            // Fallback en caso de error en la serialización
            return "{\"error\":\"Error interno del servidor\",\"status\":500}";
        }
    }
    
    /**
     * Crea la estructura base para una respuesta de error.
     * 
     * @param code Código de error específico de la aplicación
     * @param error Descripción breve del error
     * @param message Mensaje detallado para el usuario
     * @param path Ruta de la solicitud que generó el error
     * @return Mapa con la estructura base de la respuesta de error
     */
    private Map<String, Object> createBaseErrorResponse(String code, String error, String message, String path) {
        Map<String, Object> errorResponse = new HashMap<>();
        
        // Campos obligatorios
        errorResponse.put("timestamp", LocalDateTime.now().format(DATE_TIME_FORMATTER));
        errorResponse.put("error", error);
        errorResponse.put("code", code);
        errorResponse.put("message", message);
        errorResponse.put("path", path);
        
        // Campos opcionales según configuración
        if (includeTraceId) {
            // Intentar obtener el ID de trazabilidad del contexto actual
            String traceId = getTraceId();
            if (traceId != null) {
                errorResponse.put("traceId", traceId);
            }
        }
        
        if (includeSupportContact) {
            errorResponse.put("supportContact", supportContact);
        }
        
        return errorResponse;
    }
    
    /**
     * Obtiene el ID de trazabilidad del contexto actual.
     * 
     * @return ID de trazabilidad o null si no está disponible
     */
    private String getTraceId() {
        // Intentar obtener el trace ID del contexto de logging MDC o del contexto de trazabilidad
        return Optional.ofNullable(org.slf4j.MDC.get("trace_id")).orElse(null);
    }
    
    /**
     * Convierte una excepción a representación de texto.
     * 
     * @param exception Excepción a convertir
     * @return Representación de texto del stack trace
     */
    private String getStackTraceAsString(Exception exception) {
        if (exception == null) {
            return "";
        }
        
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        exception.printStackTrace(pw);
        return sw.toString();
    }
}