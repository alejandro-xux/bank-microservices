package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferFailed;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import com.alejandro.bank.notifications.MailpitInbox;
import com.alejandro.bank.notifications.TokenMockedIntegrationTest;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// BANK-80: una transferencia rechazada por el core avisa SOLO al que envía, una vez. El tipo de evento lo dice el
// header event-type; uno que este servicio no conoce no frena al consumidor. Kafka, PostgreSQL y Mailpit reales
class TransferFailedIntegrationTest extends TokenMockedIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DeliveryCircuit circuit;

    MailpitInbox inbox = inbox();

    @BeforeEach
    void cleanInbox() throws Exception {
        inbox.clear();
        circuit.reset();
    }

    @Test
    void aRejectedTransferSendsOneMailOnlyToTheSender() throws Exception {
        TransferFailed event = failed("41.13");

        publish(event.getFromAccountId(), TransferTopics.TRANSFER_FAILED, event.toByteArray());

        awaitProcessed(event.getEventId());
        List<MailpitInbox.Mail> mails = inbox.awaitAbout("41.13", 1);
        assertThat(mails).singleElement().satisfies(mail -> {
            assertThat(mail.to()).isEqualTo("ana@mail.com");
            assertThat(mail.subject()).isEqualTo("No pudimos completar tu transferencia de 41.13 PEN");
            assertThat(mail.text()).contains("Te devolvimos el dinero").contains("******0001-6")
                    .doesNotContain("Enviaste");
        });
        // Al que iba a recibir no se le avisa nada: nunca le llegó dinero
        assertThat(jdbc.queryForList("select recipient from sent_notification where event_id = ?::uuid", String.class,
                event.getEventId())).containsExactly("SENDER");
        assertThat(jdbc.queryForObject("select event_type from processed_event where event_id = ?::uuid", String.class,
                event.getEventId())).isEqualTo("TransferFailed");
    }

    @Test
    void theSameRejectionTwiceSendsOneMail() throws Exception {
        TransferFailed event = failed("42.13");

        publish(event.getFromAccountId(), TransferTopics.TRANSFER_FAILED, event.toByteArray());
        publish(event.getFromAccountId(), TransferTopics.TRANSFER_FAILED, event.toByteArray());

        awaitProcessed(event.getEventId());
        // El segundo llega después en la misma partición: cuando aparece la transferencia que va detrás, ya se procesó
        TransferCompleted after = completed("42.50");
        publish(event.getFromAccountId(), TransferTopics.TRANSFER_COMPLETED, after.toByteArray());
        awaitProcessed(after.getEventId());
        assertThat(inbox.awaitAbout("42.13", 1)).hasSize(1);
    }

    @Test
    void anEventTypeThisServiceDoesNotKnowIsSkippedAndTheNextOneIsStillNotified() throws Exception {
        // Un evento nuevo de accounts (para otro consumidor) en la misma partición, ANTES de una transferencia
        String key = UUID.randomUUID().toString();
        publish(key, "TransferScheduled", "lo que sea".getBytes(StandardCharsets.UTF_8));
        TransferCompleted next = completed("43.00");
        publish(key, TransferTopics.TRANSFER_COMPLETED, next.toByteArray());

        // Si el desconocido hubiera roto al consumidor (o lo tuviera reintentando), este nunca llegaría
        awaitProcessed(next.getEventId());
        assertThat(inbox.awaitAbout("43.00", 2)).hasSize(2);
    }

    // ---------------------------------------------------------------------------------------------------

    // Hasta 20 s a que el consumidor termine ese evento; si no, la prueba falla
    private void awaitProcessed(String eventId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from processed_event where event_id = ?::uuid)", Boolean.class, eventId))) {
            assertThat(System.currentTimeMillis()).as("evento %s procesado a tiempo", eventId).isLessThan(deadline);
            Thread.sleep(250);
        }
    }

    private static TransferFailed failed(String amount) {
        return TransferFailed.newBuilder()
                .setEventId(UUID.randomUUID().toString()).setTransferId(UUID.randomUUID().toString())
                .setFromAccountId(UUID.randomUUID().toString()).setFromAccountNumber("001-0000000001-6")
                .setFromCustomerId(ANA)
                .setAmount(amount).setCurrency("PEN").setReason("057")
                .setFailedAt(Timestamp.newBuilder().setSeconds(1792936800L))
                .build();
    }

    private static TransferCompleted completed(String amount) {
        return TransferCompleted.newBuilder()
                .setEventId(UUID.randomUUID().toString()).setTransferId(UUID.randomUUID().toString())
                .setFromAccountId(UUID.randomUUID().toString()).setFromAccountNumber("001-0000000001-6")
                .setToAccountId(UUID.randomUUID().toString()).setToAccountNumber("001-0000000003-2")
                .setFromCustomerId(ANA).setToCustomerId(LUIS)
                .setAmount(amount).setCurrency("PEN")
                .setCompletedAt(Timestamp.newBuilder().setSeconds(1792936800L))
                .build();
    }
}
