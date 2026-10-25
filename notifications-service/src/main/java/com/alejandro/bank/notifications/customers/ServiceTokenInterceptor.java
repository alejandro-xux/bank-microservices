package com.alejandro.bank.notifications.customers;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

// Pone en cada llamada gRPC el token PROPIO de notifications-service (BANK-71). A diferencia de accounts-service
// (que reenvía el token del usuario), aquí no hay usuario: el servicio pide su token a Keycloak con client
// credentials. El manager lo guarda y pide uno nuevo solo cuando le queda menos de un minuto (clockSkew)
class ServiceTokenInterceptor implements ClientInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final OAuth2AuthorizedClientManager manager;
    private final OAuth2AuthorizeRequest request;

    ServiceTokenInterceptor(OAuth2AuthorizedClientManager manager, String registrationId) {
        this.manager = manager;
        // El "principal" es el propio servicio: con él se guarda y se busca su token
        this.request = OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
                .principal("notifications-service")
                .build();
    }

    @Override
    public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
            @Override
            public void start(Listener<R> listener, Metadata headers) {
                headers.put(AUTHORIZATION, "Bearer " + manager.authorize(request).getAccessToken().getTokenValue());
                super.start(listener, headers);
            }
        };
    }
}
