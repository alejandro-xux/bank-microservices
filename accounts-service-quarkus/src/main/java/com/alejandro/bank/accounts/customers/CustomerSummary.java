package com.alejandro.bank.accounts.customers;

import com.alejandro.bank.contracts.customers.v1.CustomerStatus;

// Lo que accounts necesita saber de un cliente: el id y si está bloqueado
public record CustomerSummary(String id, CustomerStatus status) {

    static CustomerSummary from(com.alejandro.bank.contracts.customers.v1.CustomerSummary message) {
        return new CustomerSummary(message.getId(), message.getStatus());
    }

    public boolean isBlocked() {
        return status == CustomerStatus.BLOCKED;
    }
}
