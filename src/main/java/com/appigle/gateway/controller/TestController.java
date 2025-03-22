package com.appigle.gateway.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.HashMap;

/**
 * Controlador para pruebas de endpoints.
 * Útil para verificar la configuración de seguridad y enrutamiento.
 */
@RestController
@RequestMapping("/test")
public class TestController {
    
    private static final Logger logger = LoggerFactory.getLogger(TestController.class);
    
    /**
     * Endpoint de prueba para solicitudes GET.
     * 
     * @return ResponseEntity con un mensaje simple
     */
    @GetMapping("/hello")
    public ResponseEntity<Map<String, String>> testGet() {
        logger.info("GET request received on /test/hello");
        
        Map<String, String> response = new HashMap<>();
        response.put("message", "GET request successful");
        response.put("timestamp", String.valueOf(System.currentTimeMillis()));
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Endpoint de prueba para solicitudes POST.
     * 
     * @param body Cuerpo de la solicitud (opcional)
     * @return ResponseEntity con un mensaje simple y eco del cuerpo recibido
     */
    @PostMapping("/echo")
    public ResponseEntity<Map<String, Object>> testPost(@RequestBody(required = false) Map<String, Object> body) {
        logger.info("POST request received on /test/echo");
        
        Map<String, Object> response = new HashMap<>();
        response.put("message", "POST request successful");
        response.put("timestamp", System.currentTimeMillis());
        
        if (body != null && !body.isEmpty()) {
            response.put("requestBody", body);
            logger.info("Request body received: {}", body);
        } else {
            response.put("requestBody", "No body provided or empty body");
            logger.info("No request body received or empty body");
        }
        
        return ResponseEntity.ok(response);
    }
}