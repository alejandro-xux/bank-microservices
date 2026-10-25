package com.alejandro.bank.accounts.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Hace correr el relay cada 500 ms (spike BANK-66, K5: COMMIT → consumidor ~270 ms de mediana).
// Las pruebas lo apagan (bank.outbox.relay.enabled=false) y llaman a OutboxRelay a mano, sin carreras
@Component
@EnableScheduling
@ConditionalOnProperty(name = "bank.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
class OutboxPoller {

    private final OutboxRelay relay;

    // fixedDelay: la vuelta siguiente empieza 500 ms después de que terminó esta, nunca se solapan
    @Scheduled(fixedDelayString = "${bank.outbox.poll-interval-ms:500}")
    void poll() {
        try {
            // Lote lleno: puede haber más pendientes, se sigue sin esperar la próxima vuelta
            while (relay.publishBatch() == relay.batchSize()) {
                log.debug("Lote del outbox lleno, se publica el siguiente");
            }
        } catch (RuntimeException ex) {
            // Kafka no respondió a tiempo (5-10 s, ver spring.kafka.producer en config-repo): nada se marcó como
            // publicado y se reintenta en la próxima vuelta. Las transferencias siguen funcionando
            log.warn("No se pudo publicar el outbox, se reintenta en la próxima vuelta: {}", ex.getMessage());
        }
    }
}
