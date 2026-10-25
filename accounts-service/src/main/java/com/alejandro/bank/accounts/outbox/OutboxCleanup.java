package com.alejandro.bank.accounts.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

// Que la tabla outbox no crezca para siempre (BANK-81). Una fila publicada ya está en Kafka: se guarda tantos días
// como el topic guarda el mensaje (7 en Event Hubs Standard), por si hay que investigar un evento, y después se borra.
// En lotes, cada uno su propia sentencia corta: un DELETE de millones de filas en una sola transacción tendría
// tomadas esas filas y llenaría el WAL de una vez
@Slf4j
@Component
public class OutboxCleanup {

    private final OutboxStore store;
    private final Duration retention;
    private final int batchSize;

    OutboxCleanup(OutboxStore store,
                  @Value("${bank.outbox.cleanup.retention:7d}") Duration retention,
                  @Value("${bank.outbox.cleanup.batch-size:1000}") int batchSize) {
        this.store = store;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    // Devuelve cuántas filas borró
    public int clean() {
        Instant before = Instant.now().minus(retention);
        int total = 0;
        int deleted;
        do {
            deleted = store.deletePublishedBefore(before, batchSize);
            total += deleted;
        } while (deleted == batchSize);
        if (total > 0) {
            log.info("Outbox: {} eventos publicados antes de {} borrados", total, before);
        }
        return total;
    }
}
