package com.alejandro.bank.accounts.web;

import com.alejandro.bank.accounts.domain.AccountType;
import com.alejandro.bank.accounts.domain.Currency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

// Un valor fuera de los enums (por ejemplo currency "EUR") no llega a validarse: Jackson no puede
// convertirlo y responde 400 "Solicitud mal formada"
public record OpenAccountRequest(
        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                message = "debe ser un UUID")
        String customerId,

        @NotNull(message = "es obligatorio")
        AccountType type,

        @NotNull(message = "es obligatorio")
        Currency currency
) {
}
