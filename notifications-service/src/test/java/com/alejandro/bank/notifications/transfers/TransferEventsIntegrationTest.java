package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

// BANK-70: el servicio arranca con su base (Flyway V1) y el consumidor recibe los eventos de bank.transfers.v1.
// PostgreSQL y Kafka reales, con las mismas versiones e igual que el compose (sin creación automática de topics)
@SpringBootTest
class TransferEventsIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    static {
        POSTGRES.start();
        KAFKA.start();
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TransferTopics.TOPIC, 3, (short) 1),
                    new NewTopic(TransferTopics.DEAD_LETTER_TOPIC, 3, (short) 1))).all().get();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudieron crear los topics de prueba", ex);
        }
    }

    @MockitoSpyBean
    TransferNotifier notifier;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theSchemaIsCreatedByFlyway() {
        assertThat(jdbc.queryForObject("select count(*) from processed_event", Integer.class)).isZero();
    }

    @Test
    void aTransferCompletedPublishedByAccountsReachesTheNotifier() throws Exception {
        String eventId = UUID.randomUUID().toString();
        TransferCompleted event = TransferCompleted.newBuilder()
                .setEventId(eventId).setTransferId(UUID.randomUUID().toString())
                .setFromAccountNumber("001-0000000001-6").setToAccountNumber("001-0000000003-2")
                .setAmount("25.00").setCurrency("PEN").build();

        // Como lo publica el relay de accounts-service (BANK-69): bytes del Protobuf, clave = cuenta origen
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            producer.send(new ProducerRecord<>(TransferTopics.TOPIC, "cuenta-ana", event.toByteArray())).get();
        }

        verify(notifier, timeout(20_000)).notify(argThat(received -> received.getEventId().equals(eventId)
                && received.getAmount().equals("25.00")));
    }

    @Test
    void itConsumesWithItsOwnConsumerGroup() throws Exception {
        // El grupo es el de config-repo (notifications-service), no el id del listener: con el id, otro servicio
        // con un listener del mismo nombre compartiría el offset y se repartirían los mensajes
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            await(() -> admin.listGroups().all().get().stream()
                    .anyMatch(group -> group.groupId().equals("notifications-service")));
            assertThat(admin.listGroups().all().get()).extracting(group -> group.groupId())
                    .contains("notifications-service")
                    .doesNotContain(TransferEventsListener.ID);
        }
    }

    private static void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.call() && System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
        }
    }
}
