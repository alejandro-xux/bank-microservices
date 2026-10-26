package com.alejandro.bank.accounts;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;

// Lo que necesitan las pruebas, una vez para toda la suite:
// - PostgreSQL (la misma imagen que docker-compose), con el esquema de Flyway
// - un customers-service falso por gRPC en un puerto libre (un servidor grpc-java propio: un @GrpcService en las
//   pruebas no levanta servidor, spike BANK-98)
// - una llave RSA propia: los tokens de las pruebas se firman con ella (Tokens) y el servicio los valida con la
//   misma configuración OIDC de producción, solo que con esta llave pública en vez de la de Keycloak
public class AccountsTestResource implements QuarkusTestResourceLifecycleManager {

    static final String ISSUER = "https://issuer.pruebas";
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    static final FakeCustomers CUSTOMERS = new FakeCustomers();
    static final KeyPair KEYS = keys();
    static Server customersServer;

    @Override
    public Map<String, String> start() {
        POSTGRES.start();
        try {
            customersServer = ServerBuilder.forPort(0)
                    .addService(ServerInterceptors.intercept(CUSTOMERS, new FakeCustomers.TokenCapture()))
                    .build().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.of(
                "quarkus.datasource.jdbc.url", POSTGRES.getJdbcUrl(),
                "quarkus.datasource.username", POSTGRES.getUsername(),
                "quarkus.datasource.password", POSTGRES.getPassword(),
                // 127.0.0.1 y no localhost: el cliente probaba ::1 (IPv6) y el servidor escucha en IPv4
                "quarkus.grpc.clients.customers.host", "127.0.0.1",
                "quarkus.grpc.clients.customers.test-port", String.valueOf(customersServer.getPort()),
                "quarkus.oidc.public-key", Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()),
                "quarkus.oidc.token.issuer", ISSUER,
                "quarkus.oidc.token.audience", "bank-api");
    }

    @Override
    public void stop() {
        customersServer.shutdownNow();
        POSTGRES.stop();
    }

    private static KeyPair keys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
