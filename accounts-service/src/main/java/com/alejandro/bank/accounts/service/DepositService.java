package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.domain.Movement;
import com.alejandro.bank.accounts.exception.IdempotencyKeyConflictException;
import com.alejandro.bank.accounts.repository.MovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

// Depósito en ventanilla idempotente (BANK-64), con el mismo mecanismo que las transferencias (BANK-56):
// resuelve la Idempotency-Key FUERA de la transacción y delega el movimiento del dinero en DepositExecutor
@Service
@RequiredArgsConstructor
public class DepositService {

    private final MovementRepository movementRepository;
    private final DepositExecutor executor;

    // replayed = true si la clave ya tenía este mismo depósito (no se sumó otra vez)
    public record Result(Movement movement, boolean replayed) {
    }

    public Result deposit(UUID idempotencyKey, UUID accountId, BigDecimal amount) {
        String hash = RequestHash.of(accountId, amount);

        // ¿Ya se hizo? (reintento normal: la respuesta anterior se perdió)
        var existing = movementRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash, idempotencyKey);
        }
        try {
            return new Result(executor.execute(accountId, amount, idempotencyKey, hash), false);
        } catch (DataIntegrityViolationException ex) {
            // Doble clic: otra petición con la misma clave registró el depósito primero. Esta transacción
            // quedó abortada; se relee en una nueva. Si no aparece, el choque fue con otra restricción
            Movement winner = movementRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> ex);
            return replay(winner, hash, idempotencyKey);
        }
    }

    private static Result replay(Movement movement, String hash, UUID idempotencyKey) {
        if (!movement.isSameRequest(hash)) {
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }
        return new Result(movement, true);
    }
}
