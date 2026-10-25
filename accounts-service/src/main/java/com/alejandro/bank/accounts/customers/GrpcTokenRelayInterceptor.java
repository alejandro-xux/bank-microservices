package com.alejandro.bank.accounts.customers;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// Token relay (BANK-62): cada llamada gRPC lleva en la metadata el MISMO Authorization de la petición HTTP
// que se está atendiendo, así customers-service vuelve a validar el token (zero trust). Funciona porque en
// Spring MVC la llamada corre en el mismo hilo (virtual) que la petición entrante
class GrpcTokenRelayInterceptor implements ClientInterceptor {

    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of(HttpHeaders.AUTHORIZATION, Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
            @Override
            public void start(Listener<R> responseListener, Metadata headers) {
                if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                    String authorization = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
                    if (authorization != null) {
                        headers.put(AUTHORIZATION, authorization);
                    }
                }
                super.start(responseListener, headers);
            }
        };
    }
}
