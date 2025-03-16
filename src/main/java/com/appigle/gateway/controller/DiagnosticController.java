package com.appigle.gateway.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class DiagnosticController {
    
    private static final Logger logger = LoggerFactory.getLogger(DiagnosticController.class);
    
    @Value("${jwt-secret}")
    private String jwtSecret;
    
    @GetMapping("/keyvault-status")
    public ResponseEntity<Map<String, Object>> keyVaultStatus() {
        Map<String, Object> response = new HashMap<>();
        
        // No mostrar el secreto real, solo verificar si se ha cargado correctamente
        boolean secretLoaded = jwtSecret != null && !jwtSecret.isEmpty();
        boolean usingDefault = "defaultSecretKeyForDevelopmentOnly".equals(jwtSecret);
        
        response.put("keyVaultIntegrationActive", secretLoaded);
        response.put("usingDefaultSecret", usingDefault);
        response.put("secretLength", secretLoaded ? jwtSecret.length() : 0);
        
        if (secretLoaded && !usingDefault) {
            logger.info("Key Vault integration verified: JWT secret loaded successfully");
        } else if (usingDefault) {
            logger.warn("Using default JWT secret, Key Vault integration may not be working properly");
        } else {
            logger.error("Failed to load JWT secret from Key Vault");
        }
        
        return ResponseEntity.ok(response);
    }
}