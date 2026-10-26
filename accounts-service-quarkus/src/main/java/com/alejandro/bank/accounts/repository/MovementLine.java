package com.alejandro.bank.accounts.repository;

import com.alejandro.bank.accounts.domain.MovementType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Una línea del historial tal como la arma la consulta (select new …): campos sueltos, no entidades,
// así nada se carga después por su cuenta (sin N+1). counterpartyNumber es la otra cuenta de la
// transferencia, o null en un depósito
public record MovementLine(
        UUID id,
        MovementType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        UUID transferId,
        String counterpartyNumber,
        Instant createdAt
) {
}
