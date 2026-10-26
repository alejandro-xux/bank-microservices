package com.alejandro.bank.accounts.exception;

import java.util.UUID;

// 422: el saldo de la cuenta origen no alcanza para el monto
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(UUID accountId) {
        super("La cuenta " + accountId + " no tiene fondos suficientes");
    }
}
