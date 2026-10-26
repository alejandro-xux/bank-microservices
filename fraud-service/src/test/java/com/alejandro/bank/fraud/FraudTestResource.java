package com.alejandro.bank.fraud;

import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

// Lo que necesitan las pruebas, una vez para toda la suite:
// - PostgreSQL (la misma imagen que docker-compose): la base de fraude, con el esquema de Flyway
// - Kafka como el del compose: sin creación automática de topics, así que se crean aquí con las mismas particiones que
//   infra/kafka/create-topics.sh (el de transferencias y la DLT de fraude)
// - una llave RSA propia para firmar los tokens (Tokens), validados con la misma configuración OIDC de producción
// Las pruebas miran la base y Kafka desde fuera (JDBC y clientes propios), sin @Inject: así las mismas clases corren
// contra el binario nativo con @QuarkusIntegrationTest, que no inyecta nada
public class FraudTestResource implements QuarkusTestResourceLifecycleManager {

    public static final String FRAUD_DEAD_LETTER_TOPIC = "bank.fraud.v1.DLT";
    static final String ISSUER = "https://issuer.pruebas";
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    static final KeyPair KEYS = keys();

    @Override
    public Map<String, String> start() {
        POSTGRES.start();
        KAFKA.start();
        createTopics();
        return Map.of(
                "quarkus.datasource.jdbc.url", POSTGRES.getJdbcUrl(),
                "quarkus.datasource.username", POSTGRES.getUsername(),
                "quarkus.datasource.password", POSTGRES.getPassword(),
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "quarkus.oidc.public-key", Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()),
                "quarkus.oidc.token.issuer", ISSUER,
                "quarkus.oidc.token.audience", "bank-api");
    }

    @Override
    public void stop() {
        KAFKA.stop();
        POSTGRES.stop();
    }

    public static String kafkaBootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    public static String jdbcUser() {
        return POSTGRES.getUsername();
    }

    public static String jdbcPassword() {
        return POSTGRES.getPassword();
    }

    private static void createTopics() {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TransferTopics.TOPIC, 3, (short) 1),
                    new NewTopic(FRAUD_DEAD_LETTER_TOPIC, 3, (short) 1))).all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido creando los topics de prueba", e);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudieron crear los topics de prueba", e);
        }
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
