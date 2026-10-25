package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.corebanking.CoreBanking;
import com.alejandro.bank.accounts.corebanking.CoreBankingEntry;
import com.alejandro.bank.accounts.corebanking.PostingResult;
import com.alejandro.bank.accounts.domain.Transfer;
import com.alejandro.bank.accounts.exception.TransferNotFoundException;
import com.alejandro.bank.accounts.exception.IdempotencyKeyConflictException;
import com.alejandro.bank.accounts.repository.TransferRepository;
import com.alejandro.bank.accounts.security.AccountAccessPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

// Orquesta una transferencia FUERA de toda transacción (SAGA orquestada, BANK-78, spike BANK-74): autoriza (puede
// llamar a customers-service), resuelve la idempotencia y lleva la transferencia por sus pasos:
//   T1 (débito, PENDING) → asiento en el core → T2 (COMPLETED con crédito, o FAILED con REVERSAL)
// Si el core no responde algo entendible, la transferencia queda PENDING: un timeout NO es un rechazo, el core pudo
// haber registrado el asiento (S6). La resuelve después el recuperador (BANK-79)
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountAccessPolicy accessPolicy;
    private final TransferRepository transferRepository;
    private final TransferExecutor executor;
    private final CoreBanking coreBanking;

    // replayed = true si la clave ya tenía esta misma transferencia (no se movió dinero otra vez)
    public record Result(Transfer transfer, boolean replayed) {
    }

    public Result transfer(UUID idempotencyKey, UUID fromAccountId, String toAccountNumber, BigDecimal amount,
                           Authentication authentication) {
        // 1. Autorización antes que la idempotencia: con la clave de otro usuario solo se recibe 403
        accessPolicy.checkCanDebit(fromAccountId, authentication);
        String hash = RequestHash.of(fromAccountId, toAccountNumber, amount);

        // 2. ¿Ya se hizo? (reintento normal: la respuesta anterior se perdió)
        var existing = transferRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash, idempotencyKey);
        }
        TransferExecutor.Started started;
        try {
            started = executor.start(idempotencyKey, hash, requestedBy(authentication), fromAccountId, toAccountNumber, amount);
        } catch (DataIntegrityViolationException ex) {
            // 3. Doble clic: otra petición con la misma clave ganó el INSERT. Esa transacción quedó
            // abortada; se relee en una nueva. Si no aparece, el choque fue con otra restricción
            Transfer winner = transferRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> ex);
            return replay(winner, hash, idempotencyKey);
        }

        // 4. El asiento en el core, sin ninguna transacción abierta (D2), y T2 según lo que respondió
        Transfer transfer = started.transfer();
        PostingResult posted = coreBanking.post(entry(started));
        switch (posted.outcome()) {
            case POSTED -> executor.complete(transfer.getId());
            case REJECTED -> executor.fail(transfer.getId(), posted.code());
            default -> log.warn("Transferencia {} pendiente: el core no confirmó el asiento {} ({})",
                    transfer.getId(), transfer.getCoreOperation(), posted.code());
        }
        return new Result(transferRepository.findById(transfer.getId()).orElseThrow(), false);
    }

    // GET /transfers/{id}: el dueño de la cuenta origen (o el asesor) ve en qué quedó
    // Igual que en las cuentas (BANK-37): un cliente recibe el mismo 403 si la transferencia es de otro o si no existe,
    // así no puede averiguar qué ids existen; solo el asesor ve el 404
    public Transfer find(UUID transferId, Authentication authentication) {
        Transfer transfer = transferRepository.findById(transferId).orElse(null);
        if (transfer == null) {
            if (accessPolicy.isAdmin(authentication)) {
                throw new TransferNotFoundException(transferId);
            }
            throw new AccessDeniedException("Sin acceso a la transferencia");
        }
        accessPolicy.checkCanDebit(transfer.getFromAccountId(), authentication);
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
    private static String requestedBy(Authentication authentication) {
        if (authentication.getPrincipal() instanceof Jwt jwt && jwt.getClaimAsString("oid") != null) {
            return jwt.getClaimAsString("oid");
        }
        return authentication.getName();
    }
}
