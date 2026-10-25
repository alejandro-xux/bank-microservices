package com.alejandro.bank.accounts.domain;

// Estado de una transferencia en la SAGA con el core bancario (BANK-78, spike BANK-74 S1)
public enum TransferStatus {
    // Débito hecho, esperando lo que diga el core (o el recuperador, BANK-79)
    PENDING,
    // El core registró el asiento y el destino recibió el dinero
    COMPLETED,
    // El core la rechazó y el dinero volvió al origen (REVERSAL)
    FAILED
}
