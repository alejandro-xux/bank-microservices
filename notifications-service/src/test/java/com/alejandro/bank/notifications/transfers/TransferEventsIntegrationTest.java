package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import com.alejandro.bank.notifications.TokenMockedIntegrationTest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

// BANK-70: el servicio arranca con su base (Flyway V1) y el consumidor recibe los eventos de bank.transfers.v1.
class TransferEventsIntegrationTest extends TokenMockedIntegrationTest {

    @MockitoSpyBean
    TransferNotifier notifier;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Test
    void theSchemaIsCreatedByFlyway() {
        // Las tablas existen (las crean V1 y V2); su contenido depende de las otras pruebas, que comparten la base
        assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema = 'public'", String.class))
                .contains("processed_event", "sent_notification", "flyway_schema_history");
    }

    @Test
    void aTransferCompletedPublishedByAccountsReachesTheNotifier() throws Exception {
        // Monto propio de esta prueba: las de correo filtran la bandeja por monto, y la bandeja es de toda la suite
        String eventId = UUID.randomUUID().toString();
        TransferCompleted event = TransferCompleted.newBuilder()
                .setEventId(eventId).setTransferId(UUID.randomUUID().toString())
                .setFromAccountNumber("001-0000000001-6").setToAccountNumber("001-0000000003-2")
                .setFromCustomerId(ANA).setToCustomerId(LUIS)
                .setAmount("19.00").setCurrency("PEN").build();

        // Como lo publica el relay de accounts-service (BANK-69): bytes del Protobuf, clave = cuenta origen
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            producer.send(new ProducerRecord<>(TransferTopics.TOPIC, "cuenta-ana", event.toByteArray())).get();
        }

        verify(notifier, timeout(20_000)).notify(argThat(received -> received.getEventId().equals(eventId)
                && received.getAmount().equals("19.00")));
    }

    @Test
    void itConsumesWithItsOwnConsumerGroup() throws Exception {
        // El grupo es el de la configuración (en config-repo, notifications-service; en las pruebas, uno por
        // contexto), no el id del listener: con el id, otro servicio con un listener del mismo nombre compartiría
        // el offset y se repartirían los mensajes
        String configuredGroup = listeners.getListenerContainer(TransferEventsListener.ID).getGroupId();
        assertThat(configuredGroup).startsWith("notifications-test-").isNotEqualTo(TransferEventsListener.ID);
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            await(() -> admin.listGroups().all().get().stream()
                    .anyMatch(group -> group.groupId().equals(configuredGroup)));
            assertThat(admin.listGroups().all().get()).extracting(group -> group.groupId())
                    .contains(configuredGroup)
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
