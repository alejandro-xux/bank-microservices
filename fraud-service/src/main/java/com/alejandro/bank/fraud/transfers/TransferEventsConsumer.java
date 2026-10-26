package com.alejandro.bank.fraud.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import com.google.protobuf.InvalidProtocolBufferException;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

// Las transferencias completadas de bank.transfers.v1 (spike BANK-105, F1), con un grupo propio. El tipo viene en el
// header event-type, como lo publica el outbox (BANK-69):
// - TransferCompleted: se reclama (idempotente) y se revisa, todo en una transacción;
// - otro tipo (TransferFailed u otro nuevo): no le interesa a fraude y se ignora;
// - sin header, o un TransferCompleted que no se puede leer: es un mensaje roto. Falla, y SmallRye lo manda a la DLT
//   (bank.fraud.v1.DLT) sin detener el consumo
@ApplicationScoped
public class TransferEventsConsumer {

    private static final Logger LOG = Logger.getLogger(TransferEventsConsumer.class);

    private final ProcessedEvents processed;
    private final TransferReview review;

    TransferEventsConsumer(ProcessedEvents processed, TransferReview review) {
        this.processed = processed;
        this.review = review;
    }

    @Incoming("transfers")
    @Blocking
    @Transactional
    public void consume(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        Header type = record.headers().lastHeader(TransferTopics.HEADER_EVENT_TYPE);
        if (type == null) {
            throw new IllegalArgumentException("Mensaje sin header " + TransferTopics.HEADER_EVENT_TYPE
                    + " en la partición " + record.partition() + ", offset " + record.offset());
        }
        if (!TransferTopics.TRANSFER_COMPLETED.equals(new String(type.value(), StandardCharsets.UTF_8))) {
            return;
        }
        TransferCompleted event = TransferCompleted.parseFrom(record.value());
        // proto3 lee un mensaje vacío sin error: sin id, monto o fecha no es un evento válido (spike BANK-66, K8). La
        // fecha la necesita la regla de frecuencia (BANK-108)
        if (event.getEventId().isBlank() || event.getAmount().isBlank() || !event.hasCompletedAt()) {
            throw new IllegalArgumentException("TransferCompleted sin event_id, monto o completed_at, offset "
                    + record.offset());
        }
        UUID eventId = UUID.fromString(event.getEventId());
        if (!processed.claim(eventId, TransferTopics.TRANSFER_COMPLETED, EventSummary.of(event))) {
            LOG.debugf("Evento %s repetido: ya se revisó", eventId);
            return;
        }
        review.review(eventId, event);
    }
}
