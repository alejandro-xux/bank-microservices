package com.alejandro.bank.fraud;

import com.alejandro.bank.contracts.fraud.v1.FraudRule;
import com.alejandro.bank.contracts.fraud.v1.FraudSuspected;
import com.alejandro.bank.contracts.fraud.v1.FraudTopics;
import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Las reglas de fraude de punta a punta (BANK-108): el TransferCompleted entra por Kafka, la evaluación queda en
// fraud_review y una sospechosa sale como FraudSuspected en bank.fraud.v1. Cada prueba usa cuentas nuevas, así la
// historia de una no toca a otra. Sin @Inject: FraudRulesFlowIT corre esta misma clase contra el binario nativo
@QuarkusTest
@QuarkusTestResource(FraudTestResource.class)
public class FraudRulesFlowTest {

    @Test
    void anOrdinaryTransferIsRecordedWithoutAlert() throws Exception {
        TransferCompleted event = transfer(UUID.randomUUID(), UUID.randomUUID(), "150.25", "PEN", Instant.now());

        publish(event);

        await().atMost(TestKafka.WAIT).until(() -> reviews(event.getEventId()).size() == 1);
        assertThat(reviews(event.getEventId()).getFirst()).isEqualTo("false:");
        assertThat(TestKafka.read(FraudTopics.TOPIC, event.getFromAccountId())).isEmpty();
    }

    @Test
    void aHighAmountIsAnAlertWithoutPersonalData() throws Exception {
        TransferCompleted event = transfer(UUID.randomUUID(), UUID.randomUUID(), "15000.00", "PEN", Instant.now());

        publish(event);

        List<ConsumerRecord<String, byte[]>> alerts = TestKafka.awaitAny(FraudTopics.TOPIC, event.getFromAccountId());
        assertThat(alerts).hasSize(1);
        assertThat(TestKafka.header(alerts.getFirst(), TransferTopics.HEADER_EVENT_TYPE))
                .isEqualTo(FraudTopics.FRAUD_SUSPECTED);
        FraudSuspected alert = FraudSuspected.parseFrom(alerts.getFirst().value());
        assertThat(alert.getTransferEventId()).isEqualTo(event.getEventId());
        assertThat(alert.getTransferId()).isEqualTo(event.getTransferId());
        assertThat(alert.getAmount()).isEqualTo("15000.00");
        // Más de 2 000 soles a un destino nuevo: también dispara esa regla
        assertThat(alert.getRulesList())
                .containsExactly(FraudRule.FRAUD_RULE_HIGH_AMOUNT, FraudRule.FRAUD_RULE_NEW_DESTINATION_HIGH_AMOUNT);
        assertThat(alert.getEventId()).isNotBlank().isNotEqualTo(event.getEventId());
        assertThat(reviews(event.getEventId())).containsExactly("true:HIGH_AMOUNT,NEW_DESTINATION_HIGH_AMOUNT");
    }

    @Test
    void theSixthTransferInTenMinutesTriggersVelocity() throws Exception {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(300);
        List<TransferCompleted> burst = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            burst.add(transfer(from, to, "20.00", "USD", start.plusSeconds(30L * i)));
        }
        for (TransferCompleted event : burst) {
            publish(event);
        }

        List<ConsumerRecord<String, byte[]>> alerts = TestKafka.awaitAny(FraudTopics.TOPIC, from.toString());
        // Solo la sexta: las cinco primeras no pasan el límite
        assertThat(alerts).hasSize(1);
        FraudSuspected alert = FraudSuspected.parseFrom(alerts.getFirst().value());
        assertThat(alert.getTransferEventId()).isEqualTo(burst.get(5).getEventId());
        assertThat(alert.getRulesList()).containsExactly(FraudRule.FRAUD_RULE_VELOCITY);
        assertThat(reviews(burst.get(4).getEventId())).containsExactly("false:");
    }

    @Test
    void aTransferOutsideTheWindowDoesNotCount() throws Exception {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        Instant start = Instant.now().minusSeconds(3600);
        // 5 hace una hora y 1 ahora: en los últimos 10 minutos hay una sola
        List<TransferCompleted> spread = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            spread.add(transfer(from, to, "20.00", "USD", start.plusSeconds(10L * i)));
        }
        spread.add(transfer(from, to, "20.00", "USD", Instant.now()));
        for (TransferCompleted event : spread) {
            publish(event);
        }

        await().atMost(TestKafka.WAIT).until(() -> reviews(spread.get(5).getEventId()).size() == 1);
        assertThat(reviews(spread.get(5).getEventId())).containsExactly("false:");
        assertThat(TestKafka.read(FraudTopics.TOPIC, from.toString())).isEmpty();
    }

    @Test
    void aKnownDestinationWithTheSameAmountIsNotAnAlert() throws Exception {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        TransferCompleted first = transfer(from, to, "2500.00", "PEN", Instant.now().minusSeconds(60));
        TransferCompleted second = transfer(from, to, "2500.00", "PEN", Instant.now());

        publish(first);
        publish(second);

        await().atMost(TestKafka.WAIT).until(() -> reviews(second.getEventId()).size() == 1);
        assertThat(reviews(first.getEventId())).containsExactly("true:NEW_DESTINATION_HIGH_AMOUNT");
        assertThat(reviews(second.getEventId())).containsExactly("false:");
        assertThat(TestKafka.read(FraudTopics.TOPIC, from.toString())).hasSize(1);
    }

    @Test
    void theSameSuspiciousEventTwiceIsOneAlert() throws Exception {
        UUID from = UUID.randomUUID();
        TransferCompleted event = transfer(from, UUID.randomUUID(), "4000.00", "USD", Instant.now());
        TransferCompleted marker = transfer(from, UUID.randomUUID(), "10.00", "USD", Instant.now());

        publish(event);
        publish(event);
        // Misma clave, misma partición: cuando el marcador está revisado, el duplicado ya pasó
        publish(marker);

        await().atMost(TestKafka.WAIT).until(() -> reviews(marker.getEventId()).size() == 1);
        assertThat(reviews(event.getEventId())).hasSize(1);
        assertThat(TestKafka.read(FraudTopics.TOPIC, from.toString())).hasSize(1);
    }

    private static TransferCompleted transfer(UUID from, UUID to, String amount, String currency, Instant at) {
        return TransferCompleted.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTransferId(UUID.randomUUID().toString())
                .setFromAccountId(from.toString())
                .setToAccountId(to.toString())
                .setAmount(amount)
                .setCurrency(currency)
                .setCompletedAt(Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano()))
                .build();
    }

    // Clave = cuenta origen, como el outbox: los eventos de una cuenta llegan en orden
    private static void publish(TransferCompleted event) throws Exception {
        TestKafka.publish(event.getFromAccountId(), TransferTopics.TRANSFER_COMPLETED, event.toByteArray());
    }

    // Las revisiones de un evento como "sospechosa:reglas" (reglas separadas por comas)
    private static List<String> reviews(String eventId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(FraudTestResource.jdbcUrl(),
                FraudTestResource.jdbcUser(), FraudTestResource.jdbcPassword());
             PreparedStatement select = connection.prepareStatement(
                     "SELECT suspicious, rules FROM fraud_review WHERE event_id = ? ORDER BY id")) {
            select.setObject(1, UUID.fromString(eventId));
            List<String> found = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    String[] rules = (String[]) row.getArray(2).getArray();
                    found.add(row.getBoolean(1) + ":" + String.join(",", Arrays.asList(rules)));
                }
            }
            return found;
        }
    }
}
