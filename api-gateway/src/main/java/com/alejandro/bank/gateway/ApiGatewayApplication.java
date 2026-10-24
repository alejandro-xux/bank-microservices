package com.alejandro.bank.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Punto de entrada único: valida el JWT en el borde y enruta a cada servicio por su nombre en Eureka.
// Las rutas viven en config-repo/api-gateway.yml.
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
