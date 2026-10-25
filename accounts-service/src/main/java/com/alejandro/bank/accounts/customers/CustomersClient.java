package com.alejandro.bank.accounts.customers;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

// Cliente HTTP de customers-service: Feign genera la implementación. Sin "url", la dirección sale
// de Eureka por el nombre (lb://customers-service); los timeouts están en config-repo/accounts-service.yml.
// Cada llamada lleva el mismo token de la petición entrante (TokenRelayInterceptor)
@FeignClient(name = "customers-service")
public interface CustomersClient {

    @GetMapping("/customers/{id}")
    CustomerSummary findById(@PathVariable("id") String id);
}
