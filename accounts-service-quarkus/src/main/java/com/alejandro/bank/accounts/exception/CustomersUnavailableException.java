package com.alejandro.bank.accounts.exception;

// 503: customers-service no respondió, tardó demasiado o respondió con un error inesperado.
// La causa queda en el log; al cliente no se le muestran detalles internos
public class CustomersUnavailableException extends RuntimeException {

    public CustomersUnavailableException(Throwable cause) {
        super("No se pudo consultar el cliente en este momento; inténtelo nuevamente", cause);
    }
}
