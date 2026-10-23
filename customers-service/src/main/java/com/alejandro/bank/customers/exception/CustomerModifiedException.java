package com.alejandro.bank.customers.exception;

public class CustomerModifiedException extends RuntimeException {

    public CustomerModifiedException() {
        super("El cliente fue modificado por otra operación; vuelve a consultarlo e intenta de nuevo");
    }
}
