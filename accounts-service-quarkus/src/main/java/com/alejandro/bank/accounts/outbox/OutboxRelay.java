package com.alejandro.bank.accounts.outbox;

import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Future;

// Publica en Kafka los eventos pendientes del outbox, igual que OutboxRelay de accounts-service (BANK-69): mismo
// topic, misma clave, mismos headers y el mismo candado consultivo 69, así que entre las dos versiones publica una
@ApplicationScoped
public class OutboxRelay {

    // Otro relay tiene el candado: este no hizo nada
    public static final int LOCKED_BY_ANOTHER_RELAY = -1;

    @Inject
    OutboxStore store;

    @Inject
    Producer<String, byte[]> kafka;

    @ConfigProperty(name = "bank.outbox.batch-size", defaultValue = "100")
    int batchSize;

    public int batchSize() {
        return batchSize;
    }

    // Un lote, en una transacción que sostiene el candado consultivo: toma las pendientes en orden, las manda,
    // espera el "recibido" de Kafka (acks=all) de TODAS y recién entonces las marca. Si un envío falla, la excepción
    // deshace la transacción y no se marca ninguna: el lote entero se reintenta (el consumidor descarta duplicados
    // por event_id). Devuelve cuántas publicó, o LOCKED_BY_ANOTHER_RELAY
    public int publishBatch() {
        return QuarkusTransaction.requiringNew().call(() -> {
            if (!store.tryLockRelay()) {
                return LOCKED_BY_ANOTHER_RELAY;
            }
            List<OutboxStore.Message> batch = store.pending(batchSize);
            if (batch.isEmpty()) {
                return 0;
            }
            List<Future<RecordMetadata>> sent = batch.stream().map(this::send).toList();
            for (Future<RecordMetadata> confirmation : sent) {
                confirmation.get();
            }
            store.markPublished(batch.stream().map(OutboxStore.Message::id).toList());
            return batch.size();
        });
    }

    private Future<RecordMetadata> send(OutboxStore.Message message) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(TransferTopics.TOPIC, message.key(), message.payload());
        record.headers()
                .add(TransferTopics.HEADER_EVENT_TYPE, message.eventType().getBytes(StandardCharsets.UTF_8))
                .add(TransferTopics.HEADER_EVENT_ID, message.id().toString().getBytes(StandardCharsets.UTF_8));
        return kafka.send(record);
    }
}
