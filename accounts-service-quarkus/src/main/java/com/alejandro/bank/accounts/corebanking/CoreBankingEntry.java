package com.alejandro.bank.accounts.corebanking;

import com.alejandro.bank.accounts.domain.Currency;

import java.math.BigDecimal;
import java.time.Instant;

// Un asiento contable con los tipos del dominio: números de cuenta "001-0000000001-6", montos BigDecimal. El
// número de operación es el que el core usa para reconocer el asiento (id-op, 12 dígitos); lo da la secuencia de
// accounts-service y se guarda con la transferencia
public record CoreBankingEntry(long operationNumber, Instant at, String fromAccountNumber, String toAccountNumber,
                               Currency currency, BigDecimal amount) {
}
