package com.alejandro.bank.accounts.exception;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException(UUID id) {
        super("La transferencia " + id + " no existe");
    }
}
