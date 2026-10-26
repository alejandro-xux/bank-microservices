package com.alejandro.bank.fraud.rules;

import java.math.BigDecimal;

// Lo que se sabe de la cuenta origen ANTES de esta transferencia (las revisadas antes, fraud_review):
// - recentTransfers: cuántas salieron de la cuenta en la ventana de la regla de frecuencia
// - previousTransfers y averageAmount: cuántas salieron en la misma moneda y su monto promedio (null si ninguna)
// - knownDestination: si ya había transferido alguna vez a este destino
public record AccountHistory(int recentTransfers, int previousTransfers, BigDecimal averageAmount,
                             boolean knownDestination) {

    public static AccountHistory none() {
        return new AccountHistory(0, 0, null, false);
    }
}
