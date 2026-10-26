package com.alejandro.bank.accounts.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

// Origen por id (el cliente lo ve en /accounts/me); destino por número, que es lo que conoce de la
// cuenta de otra persona. El monto, como en un depósito: texto o número, leído exacto
public record TransferRequest(
        @NotNull(message = "es obligatorio")
        UUID fromAccountId,

        @NotBlank(message = "es obligatorio")
        @ValidAccountNumber
        String toAccountNumber,

        @NotNull(message = "es obligatorio")
        @DecimalMin(value = "0.01", message = "debe ser mayor que 0")
        @Digits(integer = 15, fraction = 2, message = "debe tener como máximo 15 enteros y 2 decimales")
        BigDecimal amount
) {
}
