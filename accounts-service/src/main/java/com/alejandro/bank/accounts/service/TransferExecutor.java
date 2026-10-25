package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.domain.Account;
import com.alejandro.bank.accounts.domain.Currency;
import com.alejandro.bank.accounts.domain.Movement;
import com.alejandro.bank.accounts.domain.Transfer;
import com.alejandro.bank.accounts.exception.SameAccountException;
import com.alejandro.bank.accounts.exception.TransferRejectedException;
import com.alejandro.bank.accounts.repository.AccountRepository;
import com.alejandro.bank.accounts.repository.MovementRepository;
import com.alejandro.bank.accounts.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

// La transacción de una transferencia (spike BANK-51). Es una clase aparte de TransferService porque
// @Transactional solo actúa al llamarla desde otra clase: así TransferService puede atrapar el choque
// con el UNIQUE de la clave DESPUÉS de que esta transacción se deshizo, y releer en otra
@Component
@RequiredArgsConstructor
public class TransferExecutor {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final MovementRepository movementRepository;

    // Todo o nada: el registro transfer, los dos saldos y los dos movimientos. Cualquier excepción
    // (reglas, fondos, choque de la clave) hace ROLLBACK, y entonces la clave queda libre
    @Transactional
    public Transfer execute(UUID idempotencyKey, String requestHash, String requestedBy,
                            UUID fromAccountId, String toAccountNumber, BigDecimal amount) {
        // 1. Datos para decidir, con proyecciones: ninguna cuenta queda cargada antes de su candado
        UUID toAccountId = accountRepository.findIdByNumber(toAccountNumber)
                .orElseThrow(() -> TransferRejectedException.destinationNotFound(toAccountNumber));
        if (toAccountId.equals(fromAccountId)) {
            throw new SameAccountException();
        }
        Currency currency = accountRepository.findCurrencyById(fromAccountId)
                .orElseThrow(TransferRejectedException::originNotFound);

        // 2. Reclamar la clave: el INSERT va ya (flush). Otra petición con la misma clave espera aquí,
        // en el índice UNIQUE, y al terminar esta choca con él sin haber tocado ningún saldo
        Transfer transfer = transferRepository.saveAndFlush(Transfer.create(
                idempotencyKey, requestHash, requestedBy, fromAccountId, toAccountId, amount, currency));

        // 3. Candados en orden fijo (siempre el id menor primero), no en el de la petición: dos
        // transferencias cruzadas (A→B y B→A) piden las filas en el mismo orden y nunca se bloquean
        // entre sí (deadlock)
        boolean fromFirst = fromAccountId.compareTo(toAccountId) < 0;
        Account first = lock(fromFirst ? fromAccountId : toAccountId);
        Account second = lock(fromFirst ? toAccountId : fromAccountId);
        Account from = fromFirst ? first : second;
        Account to = fromFirst ? second : first;

        // 4. Reglas con las filas ya bloqueadas, y el movimiento del dinero
        if (from.getCurrency() != to.getCurrency()) {
            throw TransferRejectedException.currencyMismatch();
        }
        from.withdraw(amount);
        to.deposit(amount);
        movementRepository.save(Movement.debit(from.getId(), amount, from.getBalance(), transfer.getId()));
        movementRepository.save(Movement.credit(to.getId(), amount, to.getBalance(), transfer.getId()));
        return transfer;
    }

    private Account lock(UUID id) {
        // Las cuentas no se borran: si se encontró por proyección, la fila existe
        return accountRepository.lockById(id).orElseThrow(() -> new IllegalStateException("Cuenta " + id + " no encontrada"));
    }
}
