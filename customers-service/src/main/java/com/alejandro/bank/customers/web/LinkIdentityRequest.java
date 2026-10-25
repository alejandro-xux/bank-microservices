package com.alejandro.bank.customers.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// Cuenta de acceso a vincular: el oid del usuario en Entra o Keycloak (un UUID)
public record LinkIdentityRequest(
        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                message = "debe ser un UUID")
        String identityId
) {
}
