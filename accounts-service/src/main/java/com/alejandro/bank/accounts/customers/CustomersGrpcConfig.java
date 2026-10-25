package com.alejandro.bank.accounts.customers;

import com.alejandro.bank.contracts.customers.v1.CustomersServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

// Cliente gRPC de customers-service (BANK-62). El canal "customers" toma su dirección de
// spring.grpc.client.channels.customers.address (config-repo/accounts-service.yml). Stub bloqueante:
// accounts-service es Spring MVC con hilos virtuales, esperar la respuesta no ocupa un hilo del sistema
@Configuration(proxyBeanMethods = false)
class CustomersGrpcConfig {

    @Bean
    CustomersServiceGrpc.CustomersServiceBlockingStub customersStub(GrpcChannelFactory channels) {
        return CustomersServiceGrpc.newBlockingStub(channels.createChannel("customers"))
                .withInterceptors(new GrpcTokenRelayInterceptor());
    }
}
