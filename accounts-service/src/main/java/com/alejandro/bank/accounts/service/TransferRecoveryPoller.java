package com.alejandro.bank.accounts.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Hace correr el recuperador cada 5 s (BANK-79). Las pruebas lo apagan (bank.saga.recovery.enabled=false) y llaman a
// TransferRecovery a mano, sin carreras
@Component
@EnableScheduling
@ConditionalOnProperty(name = "bank.saga.recovery.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
class TransferRecoveryPoller {

    private final TransferRecovery recovery;

    @Scheduled(fixedDelayString = "${bank.saga.recovery.interval-ms:5000}")
    void poll() {
        try {
            recovery.recoverPending();
        } catch (RuntimeException ex) {
            // PostgreSQL no disponible, por ejemplo: nada cambió, se reintenta en la próxima vuelta
            log.warn("El recuperador no pudo completar la vuelta: {}", ex.getMessage());
        }
    }
}
