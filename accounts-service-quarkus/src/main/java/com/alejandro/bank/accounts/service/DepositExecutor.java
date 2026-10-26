package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.domain.Account;
import com.alejandro.bank.accounts.domain.Movement;
import com.alejandro.bank.accounts.exception.AccountNotFoundException;
import com.alejandro.bank.accounts.repository.AccountRepository;
import com.alejandro.bank.accounts.repository.MovementRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

// La transacción de un depósito (como en accounts-service). Clase aparte de DepositService porque @Transactional
// (aquí el de Jakarta) solo actúa al llamar entre beans, y DepositService atrapa el choque con el UNIQUE de la clave
// después de que esta transacción se deshizo
@ApplicationScoped
public class DepositExecutor {

    @Inject
    AccountRepository accountRepository;

    @Inject
    MovementRepository movementRepository;

    // Todo o nada: el saldo nuevo y su movimiento. La fila queda bloqueada hasta el COMMIT, así dos depósitos en la
    // misma cuenta se aplican uno después del otro; un doble clic con la misma clave espera el candado y después choca
    // con el UNIQUE del movimiento (persistAndFlush): ROLLBACK, la suma no queda
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Movement execute(UUID accountId, BigDecimal amount, UUID idempotencyKey, String requestHash) {
        Account account = accountRepository.lockById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        account.deposit(amount);
        Movement movement = Movement.deposit(account.getId(), amount, account.getBalance(), idempotencyKey, requestHash);
        movementRepository.persistAndFlush(movement);
        return movement;
    }
}
