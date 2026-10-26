package com.alejandro.bank.accounts;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;

// Lo que necesitan las pruebas, una vez para toda la suite:
// - PostgreSQL (la misma imagen que docker-compose), con el esquema de Flyway
// - un customers-service falso por gRPC en un puerto libre (un servidor grpc-java propio: un @GrpcService en las
//   pruebas no levanta servidor, spike BANK-98)
// - el core bancario emulado (BANK-101): la misma imagen y los mismos mappings que el compose y que las pruebas de
//   accounts-service (.13 rechazo, .99 lento pero registrado, .98 core caído). Testcontainers la construye del mismo
//   Dockerfile con el mismo nombre: si accounts-service ya la construyó, no la vuelve a construir
// - una llave RSA propia: los tokens de las pruebas se firman con ella (Tokens) y el servicio los valida con la
//   misma configuración OIDC de producción, solo que con esta llave pública en vez de la de Keycloak
public class AccountsTestResource implements QuarkusTestResourceLifecycleManager {

    static final String ISSUER = "https://issuer.pruebas";
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    static final FakeCustomers CUSTOMERS = new FakeCustomers();
    static final GenericContainer<?> CORE_BANKING = new GenericContainer<>(
            new ImageFromDockerfile("bank-core-banking-mock-test", false)
                    .withFileFromPath("Dockerfile", Path.of("../infra/core-banking-mock/Dockerfile")))
            .withCopyFileToContainer(MountableFile.forHostPath("../infra/core-banking-mock/mappings"), "/home/wiremock/mappings")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/__admin/health"));
    static final KeyPair KEYS = keys();
    static Server customersServer;

    @Override
    public Map<String, String> start() {
        POSTGRES.start();
        CORE_BANKING.start();
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
                "bank.core-banking.url", coreBankingUrl(),
                "quarkus.oidc.public-key", Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()),
                "quarkus.oidc.token.issuer", ISSUER,
                "quarkus.oidc.token.audience", "bank-api");
    }

    @Override
    public void stop() {
        customersServer.shutdownNow();
        CORE_BANKING.stop();
        POSTGRES.stop();
    }

    static String coreBankingUrl() {
        return "http://" + CORE_BANKING.getHost() + ":" + CORE_BANKING.getMappedPort(8080);
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
