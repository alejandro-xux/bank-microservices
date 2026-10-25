package com.alejandro.bank.accounts.customers;

import com.alejandro.bank.contracts.customers.v1.CustomerStatus;

// Lo único que accounts-service necesita de un cliente. Es su propio tipo (no la clase generada), así el
// resto del servicio no depende del contrato gRPC: si mañana cambia el protocolo, solo cambia from()
public record CustomerSummary(String id, CustomerStatus status) {

    static CustomerSummary from(com.alejandro.bank.contracts.customers.v1.CustomerSummary message) {
        return new CustomerSummary(message.getId(), message.getStatus());
    }

    public boolean isBlocked() {
        return status == CustomerStatus.BLOCKED;
    }
}
