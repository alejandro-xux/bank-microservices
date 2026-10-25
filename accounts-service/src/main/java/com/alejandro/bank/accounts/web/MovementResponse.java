package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.domain.Movement;
import com.alejandro.bank.accounts.domain.MovementType;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

// Una línea del estado de cuenta. Montos como texto con 2 decimales, igual que el saldo de la cuenta
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
