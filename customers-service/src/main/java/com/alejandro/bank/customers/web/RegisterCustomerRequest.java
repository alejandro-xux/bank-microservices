package com.alejandro.bank.customers.web;

import com.alejandro.bank.customers.domain.Customer;
import com.alejandro.bank.customers.domain.DocumentType;
import com.alejandro.bank.customers.domain.Email;
import com.alejandro.bank.customers.domain.Phone;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record RegisterCustomerRequest(
        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = "\\d{8}", message = "debe tener 8 dígitos")
        String documentNumber,

        @NotBlank(message = "es obligatorio")
        @Size(max = 100, message = "no debe superar 100 caracteres")
        String firstName,

        @NotBlank(message = "es obligatorio")
        @Size(max = 50, message = "no debe superar 50 caracteres")
        String paternalLastName,

        @Size(max = 50, message = "no debe superar 50 caracteres")
        String maternalLastName,

        @Past(message = "debe ser una fecha pasada")
        LocalDate birthDate,

        @NotBlank(message = "es obligatorio")
        @jakarta.validation.constraints.Email(message = "debe ser un email válido")
        String email,

        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = "\\+?\\d{9,15}", message = "debe tener entre 9 y 15 dígitos")
        String phone
) {
    // BANK-23 registra con DNI; CE y pasaporte llegarán en otra historia
    public Customer toCustomer() {
        return Customer.builder()
                .documentType(DocumentType.DNI)
                .documentNumber(documentNumber)
                .firstName(firstName)
                .paternalLastName(paternalLastName)
                .maternalLastName(maternalLastName)
                .birthDate(birthDate)
                .emails(List.of(new Email(email, Email.Type.PERSONAL, true, false)))
                .phones(List.of(new Phone(phone, Phone.Type.MOBILE, true, false)))
                .build();
    }
}
