package com.alejandro.bank.customers.exception;

public class CustomerPreconditionFailedException extends RuntimeException {

    public CustomerPreconditionFailedException() {
        super("La versión enviada en If-Match no coincide con la versión actual del cliente");
    }
}
