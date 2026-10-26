package com.alejandro.bank.accounts;

import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

// Lo que necesitan las pruebas, una vez para toda la suite:
// - PostgreSQL (la misma imagen que docker-compose), con el esquema de Flyway
// - un customers-service falso por gRPC en un puerto libre (un servidor grpc-java propio: un @GrpcService en las
//   pruebas no levanta servidor, spike BANK-98)
// - el core bancario emulado (BANK-101): la misma imagen y los mismos mappings que el compose y que las pruebas de
//   accounts-service (.13 rechazo, .99 lento pero registrado, .98 core caído). Testcontainers la construye del mismo
//   Dockerfile con el mismo nombre: si accounts-service ya la construyó, no la vuelve a construir
// - Kafka para el outbox (BANK-102), como el del compose: sin creación automática de topics, así que se crean aquí
//   con las mismas particiones que infra/kafka/create-topics.sh
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
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    static final KeyPair KEYS = keys();
    static Server customersServer;

    @Override
    public Map<String, String> start() {
        POSTGRES.start();
        CORE_BANKING.start();
        KAFKA.start();
        createTopics();
        try {
            customersServer = ServerBuilder.forPort(0)
                    .addService(ServerInterceptors.intercept(CUSTOMERS, new FakeCustomers.TokenCapture()))
                    .build().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // El esquema lo crea la prueba con los scripts de accounts-service (src/test/resources/db/migration), no Quarkus:
        // el binario de @QuarkusIntegrationTest (BANK-109) no lleva los recursos de prueba, así que su Flyway no los
        // vería. Así la JVM y el binario parten del mismo esquema
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        return Map.ofEntries(
                Map.entry("quarkus.flyway.migrate-at-start", "false"),
                Map.entry("quarkus.datasource.jdbc.url", POSTGRES.getJdbcUrl()),
                Map.entry("quarkus.datasource.username", POSTGRES.getUsername()),
                Map.entry("quarkus.datasource.password", POSTGRES.getPassword()),
                // 127.0.0.1 y no localhost: el cliente probaba ::1 (IPv6) y el servidor escucha en IPv4
                Map.entry("quarkus.grpc.clients.customers.host", "127.0.0.1"),
                Map.entry("quarkus.grpc.clients.customers.test-port", String.valueOf(customersServer.getPort())),
                Map.entry("bank.core-banking.url", coreBankingUrl()),
                Map.entry("bank.kafka.bootstrap-servers", KAFKA.getBootstrapServers()),
                Map.entry("quarkus.oidc.public-key", Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded())),
                Map.entry("quarkus.oidc.token.issuer", ISSUER),
                Map.entry("quarkus.oidc.token.audience", "bank-api"));
    }

    @Override
    public void stop() {
        customersServer.shutdownNow();
        CORE_BANKING.stop();
        KAFKA.stop();
        POSTGRES.stop();
    }

    private static void createTopics() {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TransferTopics.TOPIC, 3, (short) 1),
                    new NewTopic(TransferTopics.DEAD_LETTER_TOPIC, 3, (short) 1))).all().get();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudieron crear los topics de prueba", ex);
        }
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
