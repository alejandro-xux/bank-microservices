package com.alejandro.bank.fraud.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;

// Lo que queda guardado de cada evento revisado (processed_event.summary), en JSON. Sin datos personales: ids, monto y
// moneda, como el propio evento.
// @RegisterForReflection: Jackson lee los componentes del record por reflexión. En JVM funciona sin nada; en el binario
// nativo solo existe lo que se registró al compilar, y sin esta anotación el resumen no se puede escribir (BANK-107)
public record EventSummary(String eventId, String transferId, String fromAccountId, String toAccountId,
                           String amount, String currency) {

    static EventSummary of(TransferCompleted event) {
        return new EventSummary(event.getEventId(), event.getTransferId(), event.getFromAccountId(),
                event.getToAccountId(), event.getAmount(), event.getCurrency());
    }
}
