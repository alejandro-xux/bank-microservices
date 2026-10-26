package com.alejandro.bank.accounts.exception;

// 400: la cuenta destino es la misma cuenta origen
public class SameAccountException extends RuntimeException {

    public SameAccountException() {
        super("La cuenta destino debe ser distinta de la cuenta origen");
    }
}
