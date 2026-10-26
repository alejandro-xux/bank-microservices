package com.alejandro.bank.accounts.outbox;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;

// Que la tabla outbox no crezca para siempre (BANK-81), como OutboxCleanup de accounts-service: lo publicado se
// guarda 7 días (lo que el topic guarda el mensaje) y después se borra, en lotes, cada uno en su propia transacción
// corta (un DELETE enorme tendría tomadas esas filas y llenaría el WAL de una vez)
@ApplicationScoped
public class OutboxCleanup {

    private static final Logger LOG = Logger.getLogger(OutboxCleanup.class);

    @Inject
    OutboxStore store;

    @ConfigProperty(name = "bank.outbox.cleanup.retention", defaultValue = "7d")
    Duration retention;

    @ConfigProperty(name = "bank.outbox.cleanup.batch-size", defaultValue = "1000")
    int batchSize;

    // Devuelve cuántas filas borró
    public int clean() {
        Instant before = Instant.now().minus(retention);
        int total = 0;
        int deleted;
        do {
            deleted = QuarkusTransaction.requiringNew().call(() -> store.deletePublishedBefore(before, batchSize));
            total += deleted;
        } while (deleted == batchSize);
        if (total > 0) {
            LOG.infof("Outbox: %d eventos publicados antes de %s borrados", total, before);
        }
        return total;
    }
}
