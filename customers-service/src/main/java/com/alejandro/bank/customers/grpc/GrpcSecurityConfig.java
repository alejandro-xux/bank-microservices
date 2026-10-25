package com.alejandro.bank.customers.grpc;

import com.alejandro.bank.customers.config.SecurityConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;
import org.springframework.security.config.Customizer;

// Seguridad del servidor gRPC (BANK-61): el JWT llega en la metadata "authorization" y lo valida el
// JwtDecoder que Spring gRPC arma con las mismas propiedades de config-repo/application.yml
// (iss, aud = bank-api, claim "roles", prefijo ROLE_): zero trust, como en REST.
// Sin token o inválido → UNAUTHENTICATED; sin el rol → PERMISSION_DENIED
@Configuration(proxyBeanMethods = false)
public class GrpcSecurityConfig {

    private static final String SERVICE = "bank.customers.v1.CustomersService/";

    // El bean no puede llamarse grpcSecurity: la autoconfiguración ya registra uno con ese nombre
    @Bean
    @GlobalServerInterceptor
    AuthenticationProcessInterceptor grpcAuthorization(GrpcSecurity grpc) throws Exception {
        return grpc
                .authorizeRequests(requests -> requests
                        // Un cliente por id: solo el asesor (como GET /customers/{id} para otro cliente)
                        .methods(SERVICE + "GetCustomer").hasAuthority("ROLE_" + SecurityConfig.ADMIN)
                        // El cliente del token: solo un CUSTOMER (como GET /customers/me)
                        .methods(SERVICE + "GetMyCustomer").hasAuthority("ROLE_" + SecurityConfig.CUSTOMER)
                        // Nombre y correo para un aviso: solo la cuenta de servicio de notifications-service
                        // (BANK-71). Ni el asesor ni accounts-service: el que no avisa no necesita el correo
                        .methods(SERVICE + "GetContact").hasAuthority("ROLE_" + SecurityConfig.NOTIFICATIONS)
                        // Las probes gRPC de AKS no traen token
                        .methods("grpc.health.v1.Health/*").permitAll()
                        // Reflection solo existe si GRPC_REFLECTION_ENABLED=true (local, para grpcurl)
                        .methods("grpc.reflection.v1.ServerReflection/*").permitAll()
                        // Cualquier método nuevo: denegado hasta que tenga su regla
                        .allRequests().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                .build();
    }
}
