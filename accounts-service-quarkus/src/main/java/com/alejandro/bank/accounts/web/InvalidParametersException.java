package com.alejandro.bank.accounts.web;

import java.util.List;
import java.util.Map;

// 400: un parámetro de la URL no cumple su regla (customerId que no es UUID, page o size fuera de rango), con la
// lista de errores por campo como en accounts-service
public class InvalidParametersException extends RuntimeException {

    private final transient List<Map<String, String>> errors;

    public InvalidParametersException(String field, String message) {
        super("Parámetro " + field + " inválido");
        this.errors = List.of(Map.of("field", field, "message", message));
    }

    public List<Map<String, String>> getErrors() {
        return errors;
    }
}
