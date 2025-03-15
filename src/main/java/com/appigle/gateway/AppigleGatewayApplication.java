// src/main/java/com/appigle/gateway/GatewayApplication.java
package com.appigle.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
//import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
//@EnableDiscoveryClient
public class AppigleGatewayApplication {
    
    public static void main(String[] args) {
        System.out.println(">>> INICIANDO GATEWAY VERSIÓN DE PRUEBA 1.3.2 <<<");
        SpringApplication.run(AppigleGatewayApplication.class, args);
    }
}

