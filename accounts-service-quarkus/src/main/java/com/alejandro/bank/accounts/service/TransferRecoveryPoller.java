package com.alejandro.bank.accounts.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

// Hace correr el recuperador cada 5 s (como TransferRecoveryPoller de accounts-service). En las pruebas
// bank.saga.recovery.every=off lo apaga y la prueba llama a TransferRecovery a mano, sin carreras
@ApplicationScoped
class TransferRecoveryPoller {

    private static final Logger LOG = Logger.getLogger(TransferRecoveryPoller.class);

    @Inject
    TransferRecovery recovery;

    @Scheduled(every = "${bank.saga.recovery.every:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        try {
            recovery.recoverPending();
        } catch (RuntimeException ex) {
            // PostgreSQL no disponible, por ejemplo: nada cambió, se reintenta en la próxima vuelta
            LOG.warnf("El recuperador no pudo completar la vuelta: %s", ex.getMessage());
        }
    }
}
