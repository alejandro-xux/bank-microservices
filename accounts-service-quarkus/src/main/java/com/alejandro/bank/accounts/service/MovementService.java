package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.repository.MovementRepository;
import com.alejandro.bank.accounts.security.AccountAccessPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.UUID;

@ApplicationScoped
public class MovementService {

    @Inject
    AccountAccessPolicy accessPolicy;

    @Inject
    MovementRepository movementRepository;

    // La misma regla del dueño que GET /accounts/{id}; el orden lo fija la consulta
    public MovementRepository.HistoryPage history(UUID accountId, int page, int size) {
        accessPolicy.accessibleAccount(accountId);
        return movementRepository.findHistory(accountId, page, size);
    }
}
