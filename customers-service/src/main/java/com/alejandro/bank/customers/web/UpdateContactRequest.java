package com.alejandro.bank.customers.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;

public record UpdateContactRequest(
        @Email(message = "debe ser un email válido")
        String email,

        @Pattern(regexp = "\\+?\\d{9,15}", message = "debe tener entre 9 y 15 dígitos")
        String phone
) {

    @JsonIgnore
    @AssertTrue(message = "debe enviar email o teléfono")
    public boolean isEmailOrPhonePresent() {
        return email != null || phone != null;
    }
}