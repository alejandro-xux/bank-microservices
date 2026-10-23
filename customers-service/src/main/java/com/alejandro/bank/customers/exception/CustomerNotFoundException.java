package com.alejandro.bank.customers.exception;

public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException(String id) {
        super("No existe un cliente con id " + id);
    }

    public CustomerNotFoundException() {
        super("No existe un cliente con ese documento");
    }
}