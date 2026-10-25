package com.alejandro.bank.accounts.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Corre la limpieza del outbox cada hora (BANK-81). Las pruebas la apagan (bank.outbox.cleanup.enabled=false) y
// llaman a OutboxCleanup a mano. Con varias instancias pueden correr dos a la vez sin problema: cada una borra
// filas que ya nadie usa, y la que llega segunda encuentra menos
@Component
@EnableScheduling
@ConditionalOnProperty(name = "bank.outbox.cleanup.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
class OutboxCleanupPoller {

    private final OutboxCleanup cleanup;

    @Scheduled(fixedDelayString = "${bank.outbox.cleanup.interval-ms:3600000}", initialDelayString = "60000")
    void clean() {
        try {
            cleanup.clean();
        } catch (RuntimeException ex) {
            log.warn("No se pudo limpiar el outbox, se reintenta en la próxima vuelta: {}", ex.getMessage());
        }
    }
}
