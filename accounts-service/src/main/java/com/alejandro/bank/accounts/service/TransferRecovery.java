package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.corebanking.CoreBanking;
import com.alejandro.bank.accounts.corebanking.CoreBankingEntry;
import com.alejandro.bank.accounts.corebanking.PostingResult;
import com.alejandro.bank.accounts.domain.Transfer;
import com.alejandro.bank.accounts.repository.AccountRepository;
import com.alejandro.bank.accounts.repository.TransferRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// El recuperador de la SAGA (BANK-79, spike BANK-74 S7): ninguna transferencia queda PENDING para siempre. Para cada
// pendiente le pregunta al core qué pasó y la termina igual que lo habría hecho el orquestador:
//   POSTED    → complete (crédito)          REJECTED → fail (REVERSAL)
//   NOT_FOUND → el core nunca la recibió: se reenvía el asiento (idempotente por id-op) y se aplica lo que responda
//   UNKNOWN   → el core sigue sin responder: se reintenta en la próxima vuelta
// Si sigue pendiente pasado el tope, se marca para revisión. Nunca compensa a ciegas: si el core sí registró el
// asiento, devolver el dinero lo dejaría en dos lugares
@Slf4j
@Component
public class TransferRecovery {

    // Otro recuperador tiene el candado: este no hizo nada
    public static final int LOCKED_BY_ANOTHER_RECOVERY = -1;

    // Número del candado consultivo: uno solo para todo el sistema (el relay del outbox usa el 69)
    private static final long RECOVERY_LOCK = 79;

    private final TransferRepository transfers;
    private final AccountRepository accounts;
    private final TransferExecutor executor;
    private final CoreBanking coreBanking;
    private final TransactionTemplate transaction;
    private final JdbcTemplate jdbc;
    private final Duration minAge;
    private final Duration reviewAfter;
    private final int batchSize;

    TransferRecovery(TransferRepository transfers, AccountRepository accounts, TransferExecutor executor,
                     CoreBanking coreBanking, TransactionTemplate transaction, JdbcTemplate jdbc,
                     @Value("${bank.saga.recovery.min-age:10s}") Duration minAge,
                     @Value("${bank.saga.recovery.review-after:30m}") Duration reviewAfter,
                     @Value("${bank.saga.recovery.batch-size:50}") int batchSize) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.executor = executor;
        this.coreBanking = coreBanking;
        this.transaction = transaction;
        this.jdbc = jdbc;
        this.minAge = minAge;
        this.reviewAfter = reviewAfter;
        this.batchSize = batchSize;
    }

    // Una vuelta. La transacción de coordinación solo sostiene el candado consultivo (un solo recuperador activo,
    // como el relay); cada T2 se confirma en la suya (REQUIRES_NEW). Aunque dos recuperadores corrieran a la vez, la
    // transición PENDING → X impide mover el dinero dos veces: el candado solo evita consultas repetidas al core.
    // Devuelve cuántas resolvió, o LOCKED_BY_ANOTHER_RECOVERY
    public int recoverPending() {
        Integer resolved = transaction.execute(status -> {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, RECOVERY_LOCK))) {
                return LOCKED_BY_ANOTHER_RECOVERY;
            }
            Instant now = Instant.now();
            List<Transfer> pending = transfers.findPendingCreatedBefore(now.minus(minAge), PageRequest.of(0, batchSize));
            int count = 0;
            for (Transfer transfer : pending) {
                if (resolve(transfer)) {
                    count++;
                } else if (transfer.getCreatedAt().isBefore(now.minus(reviewAfter)) && transfers.markForReview(transfer.getId()) == 1) {
                    log.error("Transferencia {} (id-op {}) sigue PENDING después de {}: revisar con el core. No se compensa a ciegas",
                            transfer.getId(), transfer.getCoreOperation(), reviewAfter);
                }
            }
            return count;
        });
        return resolved == null ? 0 : resolved;
    }

    private boolean resolve(Transfer transfer) {
        CoreBankingEntry entry = entry(transfer);
        PostingResult queried = coreBanking.query(entry);
        PostingResult decided = queried.outcome() == PostingResult.Outcome.NOT_FOUND ? coreBanking.post(entry) : queried;
        UUID id = transfer.getId();
        boolean resolved = switch (decided.outcome()) {
            case POSTED -> executor.complete(id);
            case REJECTED -> executor.fail(id, decided.code());
            default -> false;
        };
        log.info("Recuperador: transferencia {} (id-op {}) consulta={}{} → {}", id, transfer.getCoreOperation(),
                queried.outcome(), queried == decided ? "" : ", reenvío=" + decided.outcome(),
                resolved ? "resuelta" : "sigue pendiente");
        return resolved;
    }

    // El mismo asiento que mandó el orquestador (misma fecha y mismo id-op): el core lo reconoce
    private CoreBankingEntry entry(Transfer transfer) {
        return new CoreBankingEntry(transfer.getCoreOperation(), transfer.getCreatedAt(),
                accounts.findNumberById(transfer.getFromAccountId()).orElseThrow(),
                accounts.findNumberById(transfer.getToAccountId()).orElseThrow(),
                transfer.getCurrency(), transfer.getAmount());
    }
}
