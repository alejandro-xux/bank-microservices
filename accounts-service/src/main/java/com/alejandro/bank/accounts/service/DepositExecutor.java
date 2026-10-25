package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.domain.Account;
import com.alejandro.bank.accounts.domain.Movement;
import com.alejandro.bank.accounts.exception.AccountNotFoundException;
import com.alejandro.bank.accounts.repository.AccountRepository;
import com.alejandro.bank.accounts.repository.MovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

// La transacción de un depósito (BANK-55, idempotente desde BANK-64). Clase aparte de DepositService por
// lo mismo que TransferExecutor: @Transactional solo actúa entre clases, y DepositService atrapa el choque
// con el UNIQUE de la clave después de que esta transacción se deshizo
@Component
@RequiredArgsConstructor
public class DepositExecutor {

    private final AccountRepository accountRepository;
    private final MovementRepository movementRepository;

    // Todo o nada: el saldo nuevo y su movimiento. La fila queda bloqueada hasta el COMMIT, así dos depósitos
    // en la misma cuenta se aplican uno después del otro; un doble clic con la misma clave espera aquí el
    // candado y después choca con el UNIQUE del movimiento (saveAndFlush): ROLLBACK, la suma no queda
    @Transactional
    public Movement execute(UUID accountId, BigDecimal amount, UUID idempotencyKey, String requestHash) {
        Account account = accountRepository.lockById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        account.deposit(amount);
        return movementRepository.saveAndFlush(
                Movement.deposit(account.getId(), amount, account.getBalance(), idempotencyKey, requestHash));
    }
}
