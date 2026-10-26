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

// El mismo JSON que accounts-service: el saldo como texto con 2 decimales y las fechas ISO (comparado byte a byte en
// el spike BANK-98)
public record AccountResponse(
        UUID id,
        String number,
        String customerId,
        AccountType type,
        Currency currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal balance,
        AccountStatus status,
        Instant createdAt
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getNumber(), account.getCustomerId(), account.getType(),
                account.getCurrency(), account.getBalance().setScale(2, RoundingMode.UNNECESSARY), account.getStatus(),
                account.getCreatedAt());
    }
}
