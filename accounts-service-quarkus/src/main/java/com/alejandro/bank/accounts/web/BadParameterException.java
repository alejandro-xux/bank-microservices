package com.alejandro.bank.accounts.web;

// 400: falta un parámetro o header obligatorio, o no tiene el formato esperado (un {id} o una Idempotency-Key que no
// es UUID). Los parámetros llegan como texto y se convierten aquí: JAX-RS respondería 404 por un {id} que no se puede
// convertir, y accounts-service (Spring) responde 400
public class BadParameterException extends RuntimeException {

    public BadParameterException(String parameter) {
        super("Parámetro inválido: " + parameter);
    }
}
