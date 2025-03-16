package com.appigle.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("azure-recovery")
public class KeyVaultDiagnostic implements ApplicationListener<ApplicationReadyEvent> {
    
    private static final Logger logger = LoggerFactory.getLogger(KeyVaultDiagnostic.class);
    
    @Value("${jwt-secret:defaultSecretKeyForDevelopmentOnly}")
    private String jwtSecret;
    
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        boolean secretLoaded = jwtSecret != null && !jwtSecret.isEmpty();
        boolean usingDefault = "defaultSecretKeyForDevelopmentOnly".equals(jwtSecret);
        
        if (secretLoaded && !usingDefault) {
            logger.info("KEY VAULT INTEGRATION CHECK: SUCCESSFUL - JWT secret loaded from Key Vault (length: {})", jwtSecret.length());
        } else if (usingDefault) {
            logger.warn("KEY VAULT INTEGRATION CHECK: FAILED - Using default JWT secret, check Key Vault configuration");
        } else {
            logger.error("KEY VAULT INTEGRATION CHECK: FAILED - Unable to load JWT secret");
        }
    }
}