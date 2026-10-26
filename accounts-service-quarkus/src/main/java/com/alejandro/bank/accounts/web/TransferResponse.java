package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.domain.Currency;
import com.alejandro.bank.accounts.domain.Transfer;
import com.alejandro.bank.accounts.domain.TransferStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        UUID fromAccountId,
        UUID toAccountId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount,
        Currency currency,
        Instant createdAt,
        // SAGA con el core (BANK-78): PENDING, COMPLETED o FAILED, y por qué falló
        TransferStatus status,
        String failureReason,
        Instant resolvedAt
) {
    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getFromAccountId(),
                transfer.getToAccountId(),
                transfer.getAmount().setScale(2, RoundingMode.UNNECESSARY),
                transfer.getCurrency(),
                transfer.getCreatedAt(),
                transfer.getStatus(),
                transfer.getFailureReason(),
                transfer.getResolvedAt());
    }
}
