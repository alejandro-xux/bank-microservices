package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.domain.Account;
import com.alejandro.bank.accounts.domain.Movement;
import com.alejandro.bank.accounts.exception.AccountNotFoundException;
import com.alejandro.bank.accounts.repository.AccountRepository;
import com.alejandro.bank.accounts.repository.MovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DepositService {

    private final AccountRepository accountRepository;
    private final MovementRepository movementRepository;

    // Una sola transacción: el saldo nuevo y su movimiento se guardan juntos o ninguno (cualquier
    // excepción hace ROLLBACK). La fila de la cuenta queda bloqueada hasta el COMMIT, así dos
    // depósitos simultáneos en la misma cuenta se aplican uno después del otro
    @Transactional
    public Movement deposit(UUID accountId, BigDecimal amount) {
        Account account = accountRepository.lockById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        account.deposit(amount);
        return movementRepository.save(Movement.credit(account.getId(), amount, account.getBalance()));
    }
}
