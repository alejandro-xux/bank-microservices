package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.domain.MovementType;
import com.alejandro.bank.accounts.repository.MovementLine;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

// Una línea del estado de cuenta (BANK-57). La otra cuenta va enmascarada: se reconoce
// (oficina, últimos 4 dígitos y dígito verificador) sin exponer el número completo de otra persona
public record MovementLineResponse(
        UUID id,
        MovementType type,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal balanceAfter,
        UUID transferId,
        // 001-******0003-2; null en un depósito de ventanilla
        String counterpartyAccount,
        Instant createdAt
) {
    public static MovementLineResponse from(MovementLine line) {
        return new MovementLineResponse(
                line.id(),
                line.type(),
                line.amount().setScale(2, RoundingMode.UNNECESSARY),
                line.balanceAfter().setScale(2, RoundingMode.UNNECESSARY),
                line.transferId(),
                mask(line.counterpartyNumber()),
                line.createdAt());
    }

    // 001-0000000003-2 → 001-******0003-2
    static String mask(String number) {
        if (number == null) {
            return null;
        }
        String[] parts = number.split("-");
        return parts[0] + "-******" + parts[1].substring(6) + "-" + parts[2];
    }
}
