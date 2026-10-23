package com.alejandro.bank.customers.exception;

public class CustomerAlreadyExistsException extends RuntimeException {

    public CustomerAlreadyExistsException() {
        super("Ya existe un cliente registrado con ese documento");
    }
}