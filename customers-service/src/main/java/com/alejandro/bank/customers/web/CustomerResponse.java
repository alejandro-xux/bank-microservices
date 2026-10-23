package com.alejandro.bank.customers.web;

import com.alejandro.bank.customers.domain.Customer;
import com.alejandro.bank.customers.domain.CustomerStatus;
import com.alejandro.bank.customers.domain.DocumentType;
import com.alejandro.bank.customers.domain.Email;
import com.alejandro.bank.customers.domain.Phone;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record CustomerResponse(
        String id,
        DocumentType documentType,
        String documentNumber,
        String firstName,
        String paternalLastName,
        String maternalLastName,
        LocalDate birthDate,
        List<Email> emails,
        List<Phone> phones,
        CustomerStatus status,
        Instant createdAt
) {
    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getDocumentType(),
                customer.getDocumentNumber(),
                customer.getFirstName(),
                customer.getPaternalLastName(),
                customer.getMaternalLastName(),
                customer.getBirthDate(),
                customer.getEmails(),
                customer.getPhones(),
                customer.getStatus(),
                customer.getCreatedAt());
    }
}
