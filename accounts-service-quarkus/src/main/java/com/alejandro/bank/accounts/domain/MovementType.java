package com.alejandro.bank.accounts.domain;

// DEBIT: sale dinero de la cuenta. CREDIT: entra dinero
public enum MovementType {
    DEBIT, CREDIT,
    // Devolución al origen de una transferencia que el core rechazó (BANK-78): el débito no se borra, se compensa
    REVERSAL
}
