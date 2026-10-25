package com.alejandro.bank.accounts.outbox;

import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

// Publica en Kafka los eventos pendientes del outbox (BANK-69, spike BANK-66 K4–K7)
@Component
public class OutboxRelay {

    // Otro relay tiene el candado: este no hizo nada
    public static final int LOCKED_BY_ANOTHER_RELAY = -1;

    private final OutboxStore store;
    private final KafkaTemplate<String, byte[]> kafka;
    private final TransactionTemplate transaction;
    private final int batchSize;

    OutboxRelay(OutboxStore store, KafkaTemplate<String, byte[]> kafka, TransactionTemplate transaction,
                @Value("${bank.outbox.batch-size:100}") int batchSize) {
        this.store = store;
        this.kafka = kafka;
        this.transaction = transaction;
        this.batchSize = batchSize;
    }

    public int batchSize() {
        return batchSize;
    }

    // Un lote, en una transacción que sostiene el candado consultivo: toma las pendientes en orden, las
    // manda, espera el "recibido" de Kafka (acks=all) de TODAS y recién entonces las marca.
    // Si un envío falla, la excepción deshace la transacción y no se marca ninguna: el lote entero se
    // reintenta en la siguiente vuelta. Las que sí habían llegado se mandan otra vez (duplicados), por eso
    // el consumidor descarta por event_id (spike BANK-66, K7). Devuelve cuántas publicó, o
    // LOCKED_BY_ANOTHER_RELAY
    public int publishBatch() {
        Integer published = transaction.execute(status -> {
            if (!store.tryLockRelay()) {
                return LOCKED_BY_ANOTHER_RELAY;
            }
            List<OutboxStore.Message> batch = store.pending(batchSize);
            if (batch.isEmpty()) {
                return 0;
            }
            CompletableFuture.allOf(batch.stream().map(this::send).toArray(CompletableFuture[]::new)).join();
            store.markPublished(batch.stream().map(OutboxStore.Message::id).toList());
            return batch.size();
        });
        // Sin "== null": en Spring 7 execute devuelve lo mismo que el callback, y este siempre devuelve un número
        // (SonarCloud lo marcó como condición siempre falsa, BANK-84)
        return published;
    }

    private CompletableFuture<?> send(OutboxStore.Message message) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(TransferTopics.TOPIC, message.key(), message.payload());
        record.headers()
                .add(TransferTopics.HEADER_EVENT_TYPE, message.eventType().getBytes(StandardCharsets.UTF_8))
                .add(TransferTopics.HEADER_EVENT_ID, message.id().toString().getBytes(StandardCharsets.UTF_8));
        return kafka.send(record);
    }
}
