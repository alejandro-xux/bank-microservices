package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.domain.Account;
import com.alejandro.bank.accounts.domain.Currency;
import com.alejandro.bank.accounts.domain.Movement;
import com.alejandro.bank.accounts.domain.Timestamps;
import com.alejandro.bank.accounts.domain.Transfer;
import com.alejandro.bank.accounts.domain.TransferStatus;
import com.alejandro.bank.accounts.exception.SameAccountException;
import com.alejandro.bank.accounts.exception.TransferRejectedException;
import com.alejandro.bank.accounts.outbox.OutboxStore;
import com.alejandro.bank.accounts.outbox.TransferCompletedEvents;
import com.alejandro.bank.accounts.outbox.TransferFailedEvents;
import com.alejandro.bank.accounts.repository.AccountRepository;
import com.alejandro.bank.accounts.repository.MovementRepository;
import com.alejandro.bank.accounts.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Las transacciones de la SAGA de una transferencia (BANK-78, spike BANK-74; antes una sola, BANK-51/56). Es una
// clase aparte de TransferService porque @Transactional solo actúa al llamarla desde otra clase: así el orquestador
// puede atrapar el choque con el UNIQUE de la clave DESPUÉS de que T1 se deshizo, y llamar al core entre T1 y T2
// sin ninguna transacción abierta (D2: ningún candado de cuenta queda tomado mientras se espera a la red)
@Component
@RequiredArgsConstructor
public class TransferExecutor {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final MovementRepository movementRepository;
    private final OutboxStore outbox;

    // Lo que necesita el orquestador para el asiento: la transferencia y los números de las dos cuentas
    public record Started(Transfer transfer, String fromAccountNumber, String toAccountNumber) {
    }

    // T1: todo o nada. Reclama la clave, valida, bloquea y debita SOLO el origen (D3: el destino recibe el dinero
    // cuando el core confirma). La transferencia queda PENDING. Cualquier excepción (reglas, fondos, choque de la
    // clave) hace ROLLBACK, y entonces la clave queda libre
    @Transactional
    public Started start(UUID idempotencyKey, String requestHash, String requestedBy,
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
        Transfer transfer = transferRepository.saveAndFlush(Transfer.create(idempotencyKey, requestHash, requestedBy,
                fromAccountId, toAccountId, amount, currency, transferRepository.nextCoreOperation()));

        // 3. Candados en orden fijo y reglas con las filas ya bloqueadas. El destino tiene que poder recibir HOY:
        // no tiene sentido mandar al core una transferencia a una cuenta bloqueada
        Locked accounts = lockBoth(fromAccountId, toAccountId);
        if (accounts.from().getCurrency() != accounts.to().getCurrency()) {
            throw TransferRejectedException.currencyMismatch();
        }
        accounts.to().checkCanReceive();
        accounts.from().withdraw(amount);
        movementRepository.save(Movement.debit(fromAccountId, amount, accounts.from().getBalance(), transfer.getId()));
        return new Started(transfer, accounts.from().getNumber(), accounts.to().getNumber());
    }

    // T2 cuando el core registró el asiento: COMPLETED, crédito en el destino y el evento. false si la transferencia
    // ya estaba resuelta (otro proceso ganó la transición): entonces no se mueve nada
    // REQUIRES_NEW: el recuperador (BANK-79) la llama dentro de su transacción de coordinación, y cada T2 tiene que
    // confirmarse sola, en el momento
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(UUID transferId) {
        Instant now = Timestamps.now();
        if (transferRepository.resolve(transferId, TransferStatus.COMPLETED, null, now) == 0) {
            return false;
        }
        Transfer transfer = transferRepository.findById(transferId).orElseThrow();
        Locked accounts = lockBoth(transfer.getFromAccountId(), transfer.getToAccountId());
        accounts.to().receiveSettled(transfer.getAmount());
        movementRepository.save(Movement.credit(transfer.getToAccountId(), transfer.getAmount(),
                accounts.to().getBalance(), transferId));
        // El evento sale solo con la transferencia confirmada (Transactional Outbox, BANK-69), con las dos cuentas
        // bloqueadas: así su seq respeta el orden de cada cuenta
        outbox.save(TransferCompletedEvents.of(transfer, accounts.from(), accounts.to(), now));
        return true;
    }

    // T2 cuando el core la rechazó: FAILED y el dinero vuelve al origen con un movimiento REVERSAL (S3). false si ya
    // estaba resuelta
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(UUID transferId, String reason) {
        Instant now = Timestamps.now();
        if (transferRepository.resolve(transferId, TransferStatus.FAILED, reason, now) == 0) {
            return false;
        }
        Transfer transfer = transferRepository.findById(transferId).orElseThrow();
        Locked accounts = lockBoth(transfer.getFromAccountId(), transfer.getToAccountId());
        accounts.from().receiveSettled(transfer.getAmount());
        movementRepository.save(Movement.reversal(transfer.getFromAccountId(), transfer.getAmount(),
                accounts.from().getBalance(), transferId));
        // El aviso al que envía (BANK-80) sale solo si la compensación se confirma, igual que TransferCompleted
        outbox.save(TransferFailedEvents.of(transfer, accounts.from(), reason, now));
        return true;
    }

    private record Locked(Account from, Account to) {
    }

    // Candados en orden fijo (siempre el id menor primero), no en el de la transferencia: dos transferencias cruzadas
    // (A→B y B→A) piden las filas en el mismo orden y nunca se bloquean entre sí (deadlock)
    private Locked lockBoth(UUID fromAccountId, UUID toAccountId) {
        boolean fromFirst = fromAccountId.compareTo(toAccountId) < 0;
        Account first = lock(fromFirst ? fromAccountId : toAccountId);
        Account second = lock(fromFirst ? toAccountId : fromAccountId);
        return fromFirst ? new Locked(first, second) : new Locked(second, first);
    }

    private Account lock(UUID id) {
        // Las cuentas no se borran: si se encontró por proyección, la fila existe
        return accountRepository.lockById(id).orElseThrow(() -> new IllegalStateException("Cuenta " + id + " no encontrada"));
    }
}
