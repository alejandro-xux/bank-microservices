package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.repository.MovementLine;
import com.alejandro.bank.accounts.repository.MovementRepository;
import com.alejandro.bank.accounts.security.AccountAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MovementService {

    private final AccountAccessPolicy accessPolicy;
    private final MovementRepository movementRepository;

    // Misma regla del dueño que GET /accounts/{id} (BANK-54): el asesor cualquiera (o 404); un cliente
    // solo las suyas (ajena o inexistente → el mismo 403). El orden lo fija la consulta
    public Page<MovementLine> history(UUID accountId, int page, int size, Authentication authentication) {
        accessPolicy.accessibleAccount(accountId, authentication);
        return movementRepository.findHistory(accountId, PageRequest.of(page, size));
    }
}
