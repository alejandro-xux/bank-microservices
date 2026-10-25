package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.contracts.transfers.v1.TransferFailed;
import com.alejandro.bank.contracts.transfers.v1.TransferTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

// Una transferencia completada → un correo al que envía y otro al que recibe, o uno solo si las dos cuentas son
// del mismo cliente (BANK-72). Una rechazada → un correo solo al que envía (BANK-80): el que iba a recibir nunca
// recibió nada. Idempotente: el mismo evento dos veces manda un solo correo por destinatario
@Service
@Slf4j
@RequiredArgsConstructor
public class TransferNotifier {

    private final NotificationLog notifications;
    private final RecipientNotifier recipients;

    public void notify(TransferCompleted event) {
        if (event.getAmount().isBlank() || event.getFromCustomerId().isBlank() || event.getToCustomerId().isBlank()) {
            throw new InvalidEventException("TransferCompleted sin monto o sin clientes (evento '" + event.getEventId() + "')");
        }
        UUID eventId = validEventId(TransferTopics.TRANSFER_COMPLETED, event.getEventId());
        if (notifications.isProcessed(eventId)) {
            log.debug("Evento {} ya procesado: se descarta el duplicado", eventId);
            return;
        }
        String transferId = event.getTransferId();
        if (event.getFromCustomerId().equals(event.getToCustomerId())) {
            recipients.notify(eventId, transferId, Recipient.SELF, event.getFromCustomerId(),
                    name -> TransferMails.of(event, Recipient.SELF, name));
        } else {
            recipients.notify(eventId, transferId, Recipient.SENDER, event.getFromCustomerId(),
                    name -> TransferMails.of(event, Recipient.SENDER, name));
            recipients.notify(eventId, transferId, Recipient.RECEIVER, event.getToCustomerId(),
                    name -> TransferMails.of(event, Recipient.RECEIVER, name));
        }
        // Al final: si algo falló antes, el evento no queda como procesado y el reintento manda lo que faltó
        notifications.markProcessed(eventId, TransferTopics.TRANSFER_COMPLETED);
        log.info("Transferencia {} avisada: {} {} (evento {})", transferId, event.getAmount(), event.getCurrency(), eventId);
    }

    public void notify(TransferFailed event) {
        if (event.getAmount().isBlank() || event.getFromCustomerId().isBlank()) {
            throw new InvalidEventException("TransferFailed sin monto o sin cliente (evento '" + event.getEventId() + "')");
        }
        UUID eventId = validEventId(TransferTopics.TRANSFER_FAILED, event.getEventId());
        if (notifications.isProcessed(eventId)) {
            log.debug("Evento {} ya procesado: se descarta el duplicado", eventId);
            return;
        }
        recipients.notify(eventId, event.getTransferId(), Recipient.SENDER, event.getFromCustomerId(),
                name -> TransferMails.failed(event, name));
        notifications.markProcessed(eventId, TransferTopics.TRANSFER_FAILED);
        log.info("Transferencia {} rechazada avisada: {} {}, motivo {} (evento {})", event.getTransferId(),
                event.getAmount(), event.getCurrency(), event.getReason(), eventId);
    }

    // proto3 lee un mensaje vacío sin error (spike BANK-66, K8): sin event_id no se puede descartar un duplicado
    private static UUID validEventId(String eventType, String eventId) {
        try {
            return UUID.fromString(eventId);
        } catch (IllegalArgumentException ex) {
            throw new InvalidEventException(eventType + " sin un event_id válido ('" + eventId + "')");
        }
    }
}
