package com.alejandro.bank.accounts.exception;

import java.util.UUID;

// 409: la Idempotency-Key ya se usó para una transferencia con otros datos (no es un reintento)
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(UUID key) {
        super("La Idempotency-Key " + key + " ya se usó con otros datos; use una clave nueva para otra transferencia");
    }
}
