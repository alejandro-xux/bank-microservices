package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.corebanking.CoreBanking;
import com.alejandro.bank.accounts.corebanking.CoreBankingEntry;
import com.alejandro.bank.accounts.corebanking.PostingResult;
import com.alejandro.bank.accounts.domain.Transfer;
import com.alejandro.bank.accounts.repository.AccountRepository;
import com.alejandro.bank.accounts.repository.TransferRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// El recuperador de la SAGA, igual que en accounts-service (BANK-79): ninguna transferencia queda PENDING para siempre.
// Para cada pendiente le pregunta al core qué pasó y la termina como lo habría hecho el orquestador:
//   POSTED → complete   REJECTED → fail   NOT_FOUND → se reenvía el asiento (idempotente por id-op)   UNKNOWN → espera
// Si sigue pendiente pasado el tope, se marca para revisión. Nunca compensa a ciegas
@ApplicationScoped
public class TransferRecovery {

    private static final Logger LOG = Logger.getLogger(TransferRecovery.class);

    // Otro recuperador tiene el candado: este no hizo nada
    public static final int LOCKED_BY_ANOTHER_RECOVERY = -1;
    // El mismo candado consultivo que accounts-service: mientras convivan las dos versiones, uno solo recupera
    private static final long RECOVERY_LOCK = 79;

    @Inject
    TransferRepository transfers;

    @Inject
    AccountRepository accounts;

    @Inject
    TransferExecutor executor;

    @Inject
    CoreBanking coreBanking;

    @ConfigProperty(name = "bank.saga.recovery.min-age", defaultValue = "10s")
    Duration minAge;

    @ConfigProperty(name = "bank.saga.recovery.review-after", defaultValue = "30m")
    Duration reviewAfter;

    @ConfigProperty(name = "bank.saga.recovery.batch-size", defaultValue = "50")
    int batchSize;

    // Una vuelta. La transacción de coordinación solo sostiene el candado consultivo (un solo recuperador activo);
    // cada T2 se confirma en la suya (REQUIRES_NEW). Devuelve cuántas resolvió, o LOCKED_BY_ANOTHER_RECOVERY
    public int recoverPending() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Boolean locked = (Boolean) transfers.getEntityManager()
                    .createNativeQuery("select pg_try_advisory_xact_lock(?1)")
                    .setParameter(1, RECOVERY_LOCK).getSingleResult();
            if (!Boolean.TRUE.equals(locked)) {
                return LOCKED_BY_ANOTHER_RECOVERY;
            }
            Instant now = Instant.now();
            List<Transfer> pending = transfers.findPendingCreatedBefore(now.minus(minAge), batchSize);
            int count = 0;
            for (Transfer transfer : pending) {
                if (resolve(transfer)) {
                    count++;
                } else if (transfer.getCreatedAt().isBefore(now.minus(reviewAfter)) && transfers.markForReview(transfer.getId()) == 1) {
                    LOG.errorf("Transferencia %s (id-op %d) sigue PENDING después de %s: revisar con el core. No se compensa a ciegas",
                            transfer.getId(), transfer.getCoreOperation(), reviewAfter);
                }
            }
            return count;
        });
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
        LOG.infof("Recuperador: transferencia %s (id-op %d) consulta=%s%s → %s", id, transfer.getCoreOperation(),
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
