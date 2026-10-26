package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.corebanking.CoreBanking;
import com.alejandro.bank.accounts.corebanking.CoreBankingEntry;
import com.alejandro.bank.accounts.corebanking.PostingResult;
import com.alejandro.bank.accounts.domain.Transfer;
import com.alejandro.bank.accounts.exception.IdempotencyKeyConflictException;
import com.alejandro.bank.accounts.exception.TransferNotFoundException;
import com.alejandro.bank.accounts.repository.TransferRepository;
import com.alejandro.bank.accounts.security.AccountAccessPolicy;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.json.JsonString;
import jakarta.persistence.PersistenceException;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.UUID;

// Orquesta una transferencia FUERA de toda transacción (SAGA orquestada, como TransferService de accounts-service):
//   T1 (débito, PENDING) → asiento en el core → T2 (COMPLETED con crédito, o FAILED con REVERSAL)
// Si el core no responde algo entendible, la transferencia queda PENDING: un timeout NO es un rechazo. La resuelve
// después el recuperador
@ApplicationScoped
public class TransferService {

    private static final Logger LOG = Logger.getLogger(TransferService.class);

    @Inject
    AccountAccessPolicy accessPolicy;

    @Inject
    TransferRepository transferRepository;

    @Inject
    TransferExecutor executor;

    @Inject
    CoreBanking coreBanking;

    @Inject
    SecurityIdentity identity;

    // replayed = true si la clave ya tenía esta misma transferencia (no se movió dinero otra vez)
    public record Result(Transfer transfer, boolean replayed) {
    }

    @ActivateRequestContext
    public Result transfer(UUID idempotencyKey, UUID fromAccountId, String toAccountNumber, BigDecimal amount) {
        // 1. Autorización antes que la idempotencia: con la clave de otro usuario solo se recibe 403
        accessPolicy.checkCanDebit(fromAccountId);
        String hash = RequestHash.of(fromAccountId, toAccountNumber, amount);
        // 2. ¿Ya se hizo? (reintento normal: la respuesta anterior se perdió)
        var existing = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash, idempotencyKey);
        }
        TransferExecutor.Started started;
        try {
            started = executor.start(idempotencyKey, hash, requestedBy(), fromAccountId, toAccountNumber, amount);
        } catch (PersistenceException ex) {
            // 3. Doble clic: otra petición con la misma clave ganó el INSERT. Si no aparece, fue otra restricción
            Transfer winner = transferRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> ex);
            return replay(winner, hash, idempotencyKey);
        }
        // 4. El asiento en el core, sin ninguna transacción abierta (D2), y T2 según lo que respondió
        Transfer transfer = started.transfer();
        PostingResult posted = coreBanking.post(entry(started));
        switch (posted.outcome()) {
            case POSTED -> executor.complete(transfer.getId());
            case REJECTED -> executor.fail(transfer.getId(), posted.code());
            default -> LOG.warnf("Transferencia %s pendiente: el core no confirmó el asiento %d (%s)",
                    transfer.getId(), transfer.getCoreOperation(), posted.code());
        }
        // El estado de AHORA, leído de nuevo (T2 se confirmó en su propia transacción)
        transferRepository.getEntityManager().clear();
        return new Result(transferRepository.findById(transfer.getId()), false);
    }

    // GET /transfers/{id}: el dueño de la cuenta origen (o el asesor). Un cliente recibe el mismo 403 si es de otro o
    // si no existe; solo el asesor ve el 404
    @ActivateRequestContext
    public Transfer find(UUID transferId) {
        Transfer transfer = transferRepository.findById(transferId);
        if (transfer == null) {
            if (accessPolicy.isAdmin()) {
                throw new TransferNotFoundException(transferId);
            }
            throw new ForbiddenException("Sin acceso a la transferencia");
        }
        accessPolicy.checkCanDebit(transfer.getFromAccountId());
        return transfer;
    }

    // El asiento con los datos del dominio; el Anti-Corruption Layer lo traduce a la trama del core
    static CoreBankingEntry entry(TransferExecutor.Started started) {
        Transfer transfer = started.transfer();
        return new CoreBankingEntry(transfer.getCoreOperation(), transfer.getCreatedAt(), started.fromAccountNumber(),
                started.toAccountNumber(), transfer.getCurrency(), transfer.getAmount());
    }

    // Un reintento devuelve la transferencia en el estado en que esté AHORA (puede seguir PENDING)
    private static Result replay(Transfer transfer, String hash, UUID idempotencyKey) {
        if (!transfer.isSameRequest(hash)) {
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }
        return new Result(transfer, true);
    }

    // Quién la pidió: el oid del token (el del asesor si la ordenó él)
    private String requestedBy() {
        if (identity.getPrincipal() instanceof JsonWebToken jwt) {
            Object oid = jwt.getClaim("oid");
            // Un claim propio puede llegar como JsonString, cuyo toString() trae las comillas
            if (oid instanceof JsonString text) {
                return text.getString();
            }
            if (oid != null) {
                return oid.toString();
            }
        }
        return identity.getPrincipal().getName();
    }
}
