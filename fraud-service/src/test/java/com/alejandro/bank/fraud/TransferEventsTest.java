package com.alejandro.bank.fraud;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferFailed;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// El consumidor de transferencias completadas, de punta a punta: se publica en bank.transfers.v1 como lo hace el outbox
// de accounts (Protobuf y el tipo en el header event-type) y se mira el resultado desde fuera, con JDBC y un consumidor
// propio. Sin @Inject: TransferEventsIT corre esta misma clase contra el binario nativo
@QuarkusTest
@QuarkusTestResource(FraudTestResource.class)
public class TransferEventsTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private static KafkaProducer<String, byte[]> producer;

    @AfterAll
    static void closeProducer() {
        if (producer != null) {
            producer.close();
            producer = null;
        }
    }

    @Test
    void aCompletedTransferIsReviewedAndItsSummaryIsKept() throws Exception {
        TransferCompleted event = completed();

        publish(event.getTransferId(), TransferTopics.TRANSFER_COMPLETED, event.toByteArray());

        await().atMost(WAIT).until(() -> summary(event.getEventId()).isPresent());
        // El resumen se escribe con Jackson desde un record: en nativo depende de @RegisterForReflection (EventSummary)
        assertThat(summary(event.getEventId()).orElseThrow())
                .contains("\"transferId\": \"" + event.getTransferId() + "\"")
                .contains("\"amount\": \"150.25\"")
                .contains("\"currency\": \"PEN\"");
    }

    @Test
    void theSameEventTwiceIsReviewedOnce() throws Exception {
        TransferCompleted event = completed();
        TransferCompleted marker = completed();

        publish(event.getTransferId(), TransferTopics.TRANSFER_COMPLETED, event.toByteArray());
        publish(event.getTransferId(), TransferTopics.TRANSFER_COMPLETED, event.toByteArray());
        // Misma clave, misma partición: cuando llega el marcador, el duplicado ya pasó
        publish(event.getTransferId(), TransferTopics.TRANSFER_COMPLETED, marker.toByteArray());

        await().atMost(WAIT).until(() -> summary(marker.getEventId()).isPresent());
        assertThat(count(event.getEventId())).isEqualTo(1);
        assertThat(deadLetters(event.getTransferId())).isEmpty();
    }

    @Test
    void aFailedTransferIsIgnored() throws Exception {
        String transferId = UUID.randomUUID().toString();
        String failedEventId = UUID.randomUUID().toString();
        TransferFailed failed = TransferFailed.newBuilder().setEventId(failedEventId).setTransferId(transferId)
                .setReason("057").build();
        TransferCompleted marker = completed();

        publish(transferId, TransferTopics.TRANSFER_FAILED, failed.toByteArray());
        publish(transferId, TransferTopics.TRANSFER_COMPLETED, marker.toByteArray());

        await().atMost(WAIT).until(() -> summary(marker.getEventId()).isPresent());
        assertThat(count(failedEventId)).isZero();
        assertThat(deadLetters(transferId)).isEmpty();
    }

    @Test
    void aMessageWithoutTypeGoesToTheDeadLetterTopicAndConsumptionGoesOn() throws Exception {
        TransferCompleted event = completed();
        TransferCompleted next = completed();

        producer().send(new ProducerRecord<>(TransferTopics.TOPIC, event.getTransferId(), event.toByteArray())).get();
        publish(event.getTransferId(), TransferTopics.TRANSFER_COMPLETED, next.toByteArray());

        await().atMost(WAIT).until(() -> summary(next.getEventId()).isPresent());
        assertThat(count(event.getEventId())).isZero();
        List<ConsumerRecord<String, byte[]>> dead = awaitDeadLetters(event.getTransferId());
        assertThat(dead).hasSize(1);
        assertThat(dead.getFirst().value()).isEqualTo(event.toByteArray());
        assertThat(header(dead.getFirst(), "dead-letter-reason")).contains("event-type");
    }

    @Test
    void aCompletedTransferWithoutIdGoesToTheDeadLetterTopic() throws Exception {
        String transferId = UUID.randomUUID().toString();
        TransferCompleted broken = TransferCompleted.newBuilder().setTransferId(transferId).setAmount("10.00").build();

        publish(transferId, TransferTopics.TRANSFER_COMPLETED, broken.toByteArray());

        List<ConsumerRecord<String, byte[]>> dead = awaitDeadLetters(transferId);
        assertThat(dead).hasSize(1);
        assertThat(header(dead.getFirst(), "dead-letter-reason")).contains("event_id");
    }

    private static TransferCompleted completed() {
        return TransferCompleted.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTransferId(UUID.randomUUID().toString())
                .setFromAccountId(UUID.randomUUID().toString())
                .setToAccountId(UUID.randomUUID().toString())
                .setAmount("150.25")
                .setCurrency("PEN")
                .build();
    }

    private static void publish(String key, String type, byte[] value) throws Exception {
        ProducerRecord<String, byte[]> message = new ProducerRecord<>(TransferTopics.TOPIC, key, value);
        message.headers().add(TransferTopics.HEADER_EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        producer().send(message).get();
    }

    // Se crea al primer uso y no en un @BeforeAll: así no depende de si Quarkus ya levantó los contenedores
    private static KafkaProducer<String, byte[]> producer() {
        if (producer == null) {
            producer = new KafkaProducer<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudTestResource.kafkaBootstrapServers(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
        }
        return producer;
    }

    private static Optional<String> summary(String eventId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT summary::text FROM processed_event WHERE event_id = ?")) {
            select.setObject(1, UUID.fromString(eventId));
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(row.getString(1)) : Optional.empty();
            }
        }
    }

    private static int count(String eventId) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT count(*) FROM processed_event WHERE event_id = ?")) {
            select.setObject(1, UUID.fromString(eventId));
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(FraudTestResource.jdbcUrl(), FraudTestResource.jdbcUser(),
                FraudTestResource.jdbcPassword());
    }

    private static List<ConsumerRecord<String, byte[]>> awaitDeadLetters(String key) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        await().atMost(WAIT).until(() -> {
            found.clear();
            found.addAll(deadLetters(key));
            return !found.isEmpty();
        });
        return found;
    }

    // Lee la DLT de fraude desde el principio con un grupo nuevo y se queda con los mensajes de esa clave
    private static List<ConsumerRecord<String, byte[]>> deadLetters(String key) {
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, FraudTestResource.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "pruebas-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            consumer.subscribe(List.of(FraudTestResource.FRAUD_DEAD_LETTER_TOPIC));
            List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
            long until = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < until) {
                consumer.poll(Duration.ofMillis(500)).forEach(message -> {
                    if (key.equals(message.key())) {
                        found.add(message);
                    }
                });
            }
            return found;
        }
    }

    private static String header(ConsumerRecord<String, byte[]> message, String name) {
        return new String(message.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
