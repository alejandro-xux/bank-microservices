package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.domain.Account;
import com.alejandro.bank.accounts.domain.AccountStatus;
import com.alejandro.bank.accounts.domain.AccountType;
import com.alejandro.bank.accounts.domain.Currency;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String number,
        String customerId,
        AccountType type,
        Currency currency,
        // Dinero como texto con 2 decimales ("0.00"): un cliente JSON no lo convierte en double (spike BANK-51)
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal balance,
        AccountStatus status,
        Instant createdAt
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getNumber(),
                account.getCustomerId(),
                account.getType(),
                account.getCurrency(),
                account.getBalance().setScale(2, RoundingMode.UNNECESSARY),
                account.getStatus(),
                account.getCreatedAt());
    }
}
