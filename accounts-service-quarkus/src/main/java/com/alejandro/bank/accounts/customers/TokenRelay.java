package com.alejandro.bank.accounts.customers;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

// Reenvía a customers-service el token del usuario de esta petición (como GrpcTokenRelayInterceptor en Spring):
// customers decide con ese token (zero trust), no confía en que accounts ya lo validó
@ApplicationScoped
public class TokenRelay implements ClientInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Inject
    JsonWebToken token;

    @Override
    public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
        String raw = token.getRawToken();
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
            @Override
            public void start(Listener<R> listener, Metadata headers) {
                if (raw != null) {
                    headers.put(AUTHORIZATION, "Bearer " + raw);
                }
                super.start(listener, headers);
            }
        };
    }
}
