package com.alejandro.bank.notifications.customers;

import com.alejandro.bank.contracts.customers.v1.CustomersServiceGrpc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

// Cliente gRPC de customers-service con el token de servicio (BANK-71). El canal "customers" toma su dirección
// de spring.grpc.client.channels.customers.address; el registro OAuth2, de spring.security.oauth2.client
// (config-repo/notifications-service.yml)
@Configuration(proxyBeanMethods = false)
class CustomersGrpcConfig {

    // Para un servicio, sin petición HTTP de por medio: AuthorizedClientService (no el de request/session).
    // Solo client credentials: un servicio no tiene usuario que inicie sesión
    @Bean
    OAuth2AuthorizedClientManager serviceTokenManager(ClientRegistrationRepository registrations,
                                                      OAuth2AuthorizedClientService clients) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    @Bean
    CustomersServiceGrpc.CustomersServiceBlockingStub customersStub(
            GrpcChannelFactory channels, OAuth2AuthorizedClientManager serviceTokenManager,
            @Value("${bank.customers.token-registration:bank-api}") String registrationId) {
        return CustomersServiceGrpc.newBlockingStub(channels.createChannel("customers"))
                .withInterceptors(new ServiceTokenInterceptor(serviceTokenManager, registrationId));
    }
}
