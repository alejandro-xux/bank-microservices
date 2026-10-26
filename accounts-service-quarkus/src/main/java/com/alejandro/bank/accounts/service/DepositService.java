package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.domain.Movement;
import com.alejandro.bank.accounts.exception.IdempotencyKeyConflictException;
import com.alejandro.bank.accounts.repository.MovementRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;

import java.math.BigDecimal;
import java.util.UUID;

// Depósito idempotente (BANK-64), con el mismo mecanismo que accounts-service: resuelve la Idempotency-Key FUERA de
// la transacción y delega el movimiento del dinero en DepositExecutor
@ApplicationScoped
public class DepositService {

    @Inject
    MovementRepository movementRepository;

    @Inject
    DepositExecutor executor;

    // replayed = true si la clave ya tenía este mismo depósito (no se sumó otra vez)
    public record Result(Movement movement, boolean replayed) {
    }

    // Lee la clave fuera de una transacción: Quarkus pide para eso un contexto de petición, que por HTTP siempre existe.
    // Se activa aquí por si se llama desde otro hilo (las pruebas de concurrencia)
    @ActivateRequestContext
    public Result deposit(UUID idempotencyKey, UUID accountId, BigDecimal amount) {
        String hash = RequestHash.of(accountId, amount);
        var existing = movementRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash, idempotencyKey);
        }
        try {
            return new Result(executor.execute(accountId, amount, idempotencyKey, hash), false);
        } catch (PersistenceException ex) {
            // Doble clic: otra petición con la misma clave registró el depósito primero (en Spring era
            // DataIntegrityViolationException). Si no aparece, el choque fue con otra restricción
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
