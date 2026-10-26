package com.alejandro.bank.accounts.exception;

import java.util.UUID;

// 404: solo para el asesor. A un cliente, una cuenta inexistente le responde el mismo 403 que una ajena
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID id) {
        super("La cuenta " + id + " no existe");
    }
}
