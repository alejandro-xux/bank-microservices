package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

// Una transferencia completada → un correo al que envía y otro al que recibe, o uno solo si las dos cuentas son
// del mismo cliente (BANK-72). Idempotente: el mismo evento dos veces manda un solo correo por destinatario
@Service
@Slf4j
@RequiredArgsConstructor
public class TransferNotifier {

    private final NotificationLog notifications;
    private final RecipientNotifier recipients;

    public void notify(TransferCompleted event) {
        UUID eventId = validEventId(event);
        if (notifications.isProcessed(eventId)) {
            log.debug("Evento {} ya procesado: se descarta el duplicado", eventId);
            return;
        }
        if (event.getFromCustomerId().equals(event.getToCustomerId())) {
            recipients.notify(event, Recipient.SELF, event.getFromCustomerId());
        } else {
            recipients.notify(event, Recipient.SENDER, event.getFromCustomerId());
            recipients.notify(event, Recipient.RECEIVER, event.getToCustomerId());
        }
        // Al final: si algo falló antes, el evento no queda como procesado y el reintento manda lo que faltó
        notifications.markProcessed(eventId, TransferTopics.TRANSFER_COMPLETED);
        log.info("Transferencia {} avisada: {} {} (evento {})", event.getTransferId(), event.getAmount(),
                event.getCurrency(), eventId);
    }

    // proto3 lee un mensaje vacío sin error (spike BANK-66, K8): sin event_id no se puede descartar un duplicado, y
    // sin monto ni clientes no hay aviso que mandar
    private static UUID validEventId(TransferCompleted event) {
        if (event.getAmount().isBlank() || event.getFromCustomerId().isBlank() || event.getToCustomerId().isBlank()) {
            throw new InvalidEventException("TransferCompleted sin monto o sin clientes (evento '" + event.getEventId() + "')");
        }
        try {
            return UUID.fromString(event.getEventId());
        } catch (IllegalArgumentException ex) {
            throw new InvalidEventException("TransferCompleted sin un event_id válido ('" + event.getEventId() + "')");
        }
    }
}
