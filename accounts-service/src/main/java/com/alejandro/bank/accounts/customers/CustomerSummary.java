package com.alejandro.bank.accounts.customers;

// Lo único que accounts-service necesita de un cliente de customers-service; el resto de campos
// de su respuesta se ignora, así un campo nuevo allá no rompe este servicio
public record CustomerSummary(String id, String status) {

    public boolean isBlocked() {
        return "BLOCKED".equals(status);
    }
}
