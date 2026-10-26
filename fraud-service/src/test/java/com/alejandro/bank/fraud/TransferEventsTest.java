package com.alejandro.bank.fraud;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferFailed;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import com.google.protobuf.Timestamp;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
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

    private static final String DLT = FraudTestResource.FRAUD_DEAD_LETTER_TOPIC;

    @Test
    void aCompletedTransferIsReviewedAndItsSummaryIsKept() throws Exception {
        TransferCompleted event = completed();

        TestKafka.publish(event.getTransferId(), TransferTopics.TRANSFER_COMPLETED, event.toByteArray());

        await().atMost(TestKafka.WAIT).until(() -> summary(event.getEventId()).isPresent());
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

        TestKafka.publish(event.getTransferId(), TransferTopics.TRANSFER_COMPLETED, event.toByteArray());
        TestKafka.publish(event.getTransferId(), TransferTopics.TRANSFER_COMPLETED, event.toByteArray());
        // Misma clave, misma partición: cuando llega el marcador, el duplicado ya pasó
        TestKafka.publish(event.getTransferId(), TransferTopics.TRANSFER_COMPLETED, marker.toByteArray());

        await().atMost(TestKafka.WAIT).until(() -> summary(marker.getEventId()).isPresent());
        assertThat(count(event.getEventId())).isEqualTo(1);
        assertThat(TestKafka.read(DLT, event.getTransferId())).isEmpty();
    }

    @Test
    void aFailedTransferIsIgnored() throws Exception {
        String transferId = UUID.randomUUID().toString();
        String failedEventId = UUID.randomUUID().toString();
        TransferFailed failed = TransferFailed.newBuilder().setEventId(failedEventId).setTransferId(transferId)
                .setReason("057").build();
        TransferCompleted marker = completed();

        TestKafka.publish(transferId, TransferTopics.TRANSFER_FAILED, failed.toByteArray());
        TestKafka.publish(transferId, TransferTopics.TRANSFER_COMPLETED, marker.toByteArray());

        await().atMost(TestKafka.WAIT).until(() -> summary(marker.getEventId()).isPresent());
        assertThat(count(failedEventId)).isZero();
        assertThat(TestKafka.read(DLT, transferId)).isEmpty();
    }

    @Test
    void aMessageWithoutTypeGoesToTheDeadLetterTopicAndConsumptionGoesOn() throws Exception {
        TransferCompleted event = completed();
        TransferCompleted next = completed();

        TestKafka.publish(event.getTransferId(), null, event.toByteArray());
        TestKafka.publish(event.getTransferId(), TransferTopics.TRANSFER_COMPLETED, next.toByteArray());

        await().atMost(TestKafka.WAIT).until(() -> summary(next.getEventId()).isPresent());
        assertThat(count(event.getEventId())).isZero();
        List<ConsumerRecord<String, byte[]>> dead = TestKafka.awaitAny(DLT, event.getTransferId());
        assertThat(dead).hasSize(1);
        assertThat(dead.getFirst().value()).isEqualTo(event.toByteArray());
        assertThat(TestKafka.header(dead.getFirst(), "dead-letter-reason")).contains("event-type");
    }

    @Test
    void aCompletedTransferWithoutIdGoesToTheDeadLetterTopic() throws Exception {
        String transferId = UUID.randomUUID().toString();
        TransferCompleted broken = TransferCompleted.newBuilder().setTransferId(transferId).setAmount("10.00").build();

        TestKafka.publish(transferId, TransferTopics.TRANSFER_COMPLETED, broken.toByteArray());

        List<ConsumerRecord<String, byte[]>> dead = TestKafka.awaitAny(DLT, transferId);
        assertThat(dead).hasSize(1);
        assertThat(TestKafka.header(dead.getFirst(), "dead-letter-reason")).contains("event_id");
    }

    private static TransferCompleted completed() {
        return TransferCompleted.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTransferId(UUID.randomUUID().toString())
                .setFromAccountId(UUID.randomUUID().toString())
                .setToAccountId(UUID.randomUUID().toString())
                .setAmount("150.25")
                .setCurrency("PEN")
                .setCompletedAt(Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()))
                .build();
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



}
