package com.alejandro.bank.accounts.web;

// Headers de las operaciones idempotentes: transferencias (BANK-56) y depósitos (BANK-64)
final class IdempotencyHeaders {

    // UUID que el cliente genera por operación y repite en cada reintento
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    // En la respuesta: esta operación ya se había hecho con esa clave; no se repitió
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private IdempotencyHeaders() {
    }
}
