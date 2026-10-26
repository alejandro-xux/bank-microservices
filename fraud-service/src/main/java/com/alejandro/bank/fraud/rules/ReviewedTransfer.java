package com.alejandro.bank.fraud.rules;

import java.math.BigDecimal;
import java.time.Instant;

// La transferencia que se revisa, con lo que dice su evento. completedAt es el momento en que se registró (el del
// evento, no el de la revisión): así la regla de frecuencia da lo mismo si el consumidor se atrasó o se pone al día
public record ReviewedTransfer(String transferId, String fromAccountId, String toAccountId, BigDecimal amount,
                               String currency, Instant completedAt) {
}
