package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import com.alejandro.bank.notifications.MailpitInbox;
import com.alejandro.bank.notifications.TokenMockedIntegrationTest;
import com.google.protobuf.Timestamp;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// BANK-72: cada transferencia avisa por correo al que envía y al que recibe, una sola vez aunque el evento llegue
// repetido. Kafka, PostgreSQL y Mailpit reales; customers-service simulado
class TransferMailIntegrationTest extends TokenMockedIntegrationTest {

    @Autowired
    TransferNotifier notifier;

    @Autowired
    JdbcTemplate jdbc;

    MailpitInbox inbox = inbox();

    @BeforeEach
    void cleanInbox() throws Exception {
        inbox.clear();
    }

    @Test
    void aTransferPublishedInKafkaSendsOneMailToEachSide() throws Exception {
        TransferCompleted event = transfer(ANA, LUIS, "25.00");

        publish(event);

        List<MailpitInbox.Mail> mails = inbox.awaitAbout("25.00", 2);
        assertThat(mails).extracting(MailpitInbox.Mail::to, MailpitInbox.Mail::subject)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ana@mail.com", "Enviaste 25.00 PEN"),
                        org.assertj.core.groups.Tuple.tuple("luis@mail.com", "Recibiste 25.00 PEN"));
        assertThat(mails).allSatisfy(mail -> assertThat(mail.text()).doesNotContain("0000000001").contains("******0001-6"));
        // Y con el token de servicio, no con el de una persona
        assertThat(RECEIVED_AUTHORIZATIONS).containsOnly("Bearer token-servicio");
    }

    @Test
    void theSameEventTwiceSendsOneMailPerRecipient() throws Exception {
        TransferCompleted event = transfer(ANA, LUIS, "26.00");

        publish(event);
        publish(event);

        assertThat(inbox.awaitAbout("26.00", 2)).hasSize(2);
    }

    @Test
    void twoConsumersWithTheSameEventAtOnceSendOneMailPerRecipient() throws Exception {
        // Como dos instancias de notifications-service que reciben el mismo evento (al menos una vez)
        TransferCompleted event = transfer(ANA, LUIS, "27.00");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> consumers = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                consumers.add(pool.submit((Callable<Void>) () -> {
                    start.await();
                    notifier.notify(event);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> consumer : consumers) {
                consumer.get();
            }
        }

        assertThat(inbox.awaitAbout("27.00", 2)).hasSize(2);
    }

    @Test
    void betweenOwnAccountsItIsASingleMail() throws Exception {
        notifier.notify(transfer(ANA, ANA, "28.00"));

        assertThat(inbox.awaitAbout("28.00", 1)).extracting(MailpitInbox.Mail::subject).containsExactly("Moviste 28.00 PEN entre tus cuentas");
    }

    @Test
    void anUnknownReceiverDoesNotStopTheSendersMail() throws Exception {
        TransferCompleted event = transfer(ANA, MISSING, "29.00");

        notifier.notify(event);

        assertThat(inbox.awaitAbout("29.00", 1)).extracting(MailpitInbox.Mail::to).containsExactly("ana@mail.com");
        // Queda registrado como no enviado (no se reintenta) y el evento, procesado
        assertThat(jdbc.queryForObject("select sent from sent_notification where event_id = ?::uuid and recipient = 'RECEIVER'",
                Boolean.class, event.getEventId())).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from processed_event where event_id = ?::uuid",
                Integer.class, event.getEventId())).isEqualTo(1);
    }

    @Test
    void whenCustomersIsDownNothingIsMarkedAndTheRetrySendsIt() throws Exception {
        TransferCompleted event = transfer(ANA, DOWN, "30.00");

        assertThatThrownBy(() -> notifier.notify(event)).isInstanceOf(RuntimeException.class);

        // El correo del que envía sí salió y quedó registrado; el del que recibe no se reclamó (ROLLBACK)
        assertThat(jdbc.queryForObject("select count(*) from sent_notification where event_id = ?::uuid and recipient = 'RECEIVER'",
                Integer.class, event.getEventId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from processed_event where event_id = ?::uuid",
                Integer.class, event.getEventId())).isZero();
        // Al reintentar, el del que envía no se repite
        assertThatThrownBy(() -> notifier.notify(event)).isInstanceOf(RuntimeException.class);
        assertThat(inbox.awaitAbout("30.00", 1)).extracting(MailpitInbox.Mail::to).containsExactly("ana@mail.com");
    }

    @Test
    void anEventWithoutIdOrAmountIsInvalidAndSendsNothing() throws Exception {
        assertThatThrownBy(() -> notifier.notify(TransferCompleted.getDefaultInstance())).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> notifier.notify(transfer(ANA, LUIS, "31.00").toBuilder().setEventId("no-es-un-uuid").build()))
                .isInstanceOf(InvalidEventException.class);

        assertThat(inbox.awaitAbout("31.00", 0)).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------

    private static TransferCompleted transfer(String from, String to, String amount) {
        return TransferCompleted.newBuilder()
                .setEventId(UUID.randomUUID().toString()).setTransferId(UUID.randomUUID().toString())
                .setFromAccountId(UUID.randomUUID().toString()).setFromAccountNumber("001-0000000001-6")
                .setToAccountId(UUID.randomUUID().toString()).setToAccountNumber("001-0000000003-2")
                .setFromCustomerId(from).setToCustomerId(to)
                .setAmount(amount).setCurrency("PEN")
                .setCompletedAt(Timestamp.newBuilder().setSeconds(1792936800L))
                .build();
    }

    private static void publish(TransferCompleted event) throws Exception {
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            producer.send(new ProducerRecord<>(TransferTopics.TOPIC, event.getFromAccountId(), event.toByteArray())).get();
        }
    }
}
