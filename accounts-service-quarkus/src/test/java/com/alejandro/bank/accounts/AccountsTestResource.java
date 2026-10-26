package com.alejandro.bank.accounts;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

// Lo que necesitan las pruebas, una vez para toda la suite: PostgreSQL (la misma imagen que docker-compose) con el
// esquema de Flyway, y un customers-service falso por gRPC en un puerto libre. Es un servidor grpc-java propio: un
// @GrpcService en las pruebas no levanta servidor (spike BANK-98)
public class AccountsTestResource implements QuarkusTestResourceLifecycleManager {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    static Server customers;

    @Override
    public Map<String, String> start() {
        POSTGRES.start();
        try {
            customers = ServerBuilder.forPort(0).addService(new FakeCustomers()).build().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.of(
                "quarkus.datasource.jdbc.url", POSTGRES.getJdbcUrl(),
                "quarkus.datasource.username", POSTGRES.getUsername(),
                "quarkus.datasource.password", POSTGRES.getPassword(),
                // 127.0.0.1 y no localhost: el cliente probaba ::1 (IPv6) y el servidor escucha en IPv4
                "quarkus.grpc.clients.customers.host", "127.0.0.1",
                "quarkus.grpc.clients.customers.test-port", String.valueOf(customers.getPort()));
    }

    @Override
    public void stop() {
        customers.shutdownNow();
        POSTGRES.stop();
    }
}
