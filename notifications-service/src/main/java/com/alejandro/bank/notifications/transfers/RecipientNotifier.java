package com.alejandro.bank.notifications.transfers;

import com.alejandro.bank.contracts.transfers.v1.TransferCompleted;
import com.alejandro.bank.notifications.customers.CustomerContacts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

// El aviso a UN destinatario, en una transacción (BANK-72): reclamarlo, pedir su contacto y mandarlo. Clase aparte
// de TransferNotifier porque @Transactional solo actúa al llamarla desde otra clase
@Slf4j
@Component
@RequiredArgsConstructor
class RecipientNotifier {

    private final NotificationLog sentLog;
    private final CustomerContacts contacts;
    private final JavaMailSender mail;
    private final DeliveryCircuit circuit;

    @Value("${bank.notifications.mail.from:avisos@bank.local}")
    private String from;

    // Si el SMTP o customers-service fallan, la excepción deshace el reclamo y el aviso se reintenta.
    // Si el proceso muere justo después de mandar y antes del COMMIT, el reintento lo manda otra vez: igual que el
    // relay de accounts, la garantía es "al menos una vez"
    @Transactional
    public void notify(TransferCompleted event, Recipient recipient, String customerId) {
        UUID eventId = UUID.fromString(event.getEventId());
        if (!sentLog.claim(eventId, recipient, customerId)) {
            log.debug("Aviso {} del evento {} ya enviado", recipient, eventId);
            return;
        }
        // Contacto y correo, dentro del circuit breaker (BANK-73): con el circuito abierto ni se intenta
        boolean sent = circuit.call(() -> deliver(event, recipient, customerId));
        if (!sent) {
            sentLog.markNotSent(eventId, recipient);
        }
    }

    // true si se mandó; false si no había a quién (cliente inexistente o sin correo), que no se reintenta
    private boolean deliver(TransferCompleted event, Recipient recipient, String customerId) {
        Optional<CustomerContacts.Contact> contact = contacts.find(customerId).filter(c -> !c.email().isBlank());
        if (contact.isEmpty()) {
            log.warn("Sin correo para el cliente {}: no se le avisa la transferencia {}", customerId, event.getTransferId());
            return false;
        }
        TransferMails.Mail content = TransferMails.of(event, recipient, contact.get().name());
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(contact.get().email());
        message.setSubject(content.subject());
        message.setText(content.text());
        mail.send(message);
        return true;
    }
}
