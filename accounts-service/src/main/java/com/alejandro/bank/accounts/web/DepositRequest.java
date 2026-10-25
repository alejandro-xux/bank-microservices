package com.alejandro.bank.accounts.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

// El monto llega como texto ("150.00") o como número (150.00): Jackson lo convierte a BigDecimal
// leyendo el texto exacto del JSON, sin pasar por double. Va en la moneda de la cuenta.
// Máximo 15 enteros: el saldo es NUMERIC(19,2) y la suma no debe desbordarlo
public record DepositRequest(
        @NotNull(message = "es obligatorio")
        @DecimalMin(value = "0.01", message = "debe ser mayor que 0")
        @Digits(integer = 15, fraction = 2, message = "debe tener como máximo 15 enteros y 2 decimales")
        BigDecimal amount
) {
}
