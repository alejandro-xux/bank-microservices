package com.alejandro.bank.accounts.exception;

import com.alejandro.bank.accounts.domain.AccountStatus;

import java.util.UUID;

// 422: la cuenta existe pero está bloqueada o cerrada; no puede recibir ni mover dinero
public class AccountNotActiveException extends RuntimeException {

    public AccountNotActiveException(UUID id, AccountStatus status) {
        super("La cuenta " + id + " no está activa (" + status + ")");
    }
}
