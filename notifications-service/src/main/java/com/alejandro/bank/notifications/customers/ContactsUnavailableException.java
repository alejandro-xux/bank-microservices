package com.alejandro.bank.notifications.customers;

// customers-service (o Keycloak) no respondió a tiempo: es pasajero, el aviso se reintenta (BANK-73)
public class ContactsUnavailableException extends RuntimeException {

    public ContactsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
