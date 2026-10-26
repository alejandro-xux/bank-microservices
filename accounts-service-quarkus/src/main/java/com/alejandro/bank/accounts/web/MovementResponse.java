package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.domain.Movement;
import com.alejandro.bank.accounts.domain.MovementType;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

// Una línea del estado de cuenta. Montos como texto con 2 decimales, igual que el saldo de la cuenta.
// @RegisterForReflection (BANK-109): el depósito lo devuelve dentro de un Response (para el 201 y su Location), así que
// Quarkus no lo ve como tipo de respuesta y no lo registra para Jackson. En JVM da igual; en el binario nativo el
// depósito respondía 500 ("Jackson was unable to serialize type MovementResponse"). Lo encontró AccountsNativeIT
@RegisterForReflection
public record MovementResponse(
        UUID id,
        UUID accountId,
        MovementType type,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount,
        // Saldo de la cuenta después de este movimiento
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal balanceAfter,
        // Vacío en un depósito de ventanilla
        UUID transferId,
        Instant createdAt
) {
    public static MovementResponse from(Movement movement) {
        return new MovementResponse(
                movement.getId(),
                movement.getAccountId(),
                movement.getType(),
                movement.getAmount().setScale(2, RoundingMode.UNNECESSARY),
                movement.getBalanceAfter().setScale(2, RoundingMode.UNNECESSARY),
                movement.getTransferId(),
                movement.getCreatedAt());
    }
}
