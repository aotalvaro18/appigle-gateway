package com.appigle.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Configuración para integración con Azure Key Vault.
 * 
 * Esta clase configura la integración con Azure Key Vault para obtener secretos
 * de forma segura utilizando la identidad administrada del Container App.
 * 
 * La configuración principal se realiza mediante propiedades en el archivo
 * application-azure.yml, y esta clase sirve principalmente para documentar
 * y centralizar aspectos relacionados con Key Vault.
 */
@Configuration
@Profile("azure-recovery")
public class KeyVaultConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(KeyVaultConfig.class);
    
    /**
     * Constructor que registra información sobre la integración con Key Vault.
     */
    public KeyVaultConfig() {
        logger.info("Inicializando integración con Azure Key Vault");
        logger.info("Key Vault configurado para obtener secretos usando identidad administrada");
        
        // La biblioteca spring-cloud-azure-starter-keyvault-secrets maneja 
        // automáticamente la autenticación y recuperación de secretos
    }
}