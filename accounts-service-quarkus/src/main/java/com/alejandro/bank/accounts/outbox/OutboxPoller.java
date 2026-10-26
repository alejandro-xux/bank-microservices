package com.alejandro.bank.accounts.outbox;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

// Los procesos de fondo del outbox, como OutboxPoller y OutboxCleanupPoller de accounts-service. En las pruebas
// bank.outbox.relay.every y bank.outbox.cleanup.every=off los apagan y la prueba los llama a mano, sin carreras.
// SKIP: una vuelta nunca se solapa con la anterior (el fixedDelay de Spring)
@ApplicationScoped
class OutboxPoller {

    private static final Logger LOG = Logger.getLogger(OutboxPoller.class);

    @Inject
    OutboxRelay relay;

    @Inject
    OutboxCleanup cleanup;

    // Cada 500 ms (spike BANK-66, K5)
    @Scheduled(every = "${bank.outbox.relay.every:0.5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void publish() {
        try {
            // Lote lleno: puede haber más pendientes, se sigue sin esperar la próxima vuelta
            while (relay.publishBatch() == relay.batchSize()) {
                LOG.debug("Lote del outbox lleno, se publica el siguiente");
            }
        } catch (RuntimeException ex) {
            // Kafka no respondió a tiempo (5-10 s, ver OutboxKafka): nada se marcó como publicado y se reintenta
            // en la próxima vuelta. Las transferencias siguen funcionando
            LOG.warnf("No se pudo publicar el outbox, se reintenta en la próxima vuelta: %s", ex.getMessage());
        }
    }

    // Cada hora, la primera al minuto de arrancar
    @Scheduled(every = "${bank.outbox.cleanup.every:1h}", delayed = "60s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void clean() {
        try {
            cleanup.clean();
        } catch (RuntimeException ex) {
            LOG.warnf("No se pudo limpiar el outbox, se reintenta en la próxima vuelta: %s", ex.getMessage());
        }
    }
}
