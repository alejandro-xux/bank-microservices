package com.alejandro.bank.notifications.transfers;

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
import java.util.function.Function;

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
    // relay de accounts, la garantía es "al menos una vez". El correo lo arma quien llama (según el evento, BANK-80)
    // a partir del nombre del cliente
    @Transactional
    public void notify(UUID eventId, String transferId, Recipient recipient, String customerId,
                       Function<String, TransferMails.Mail> mailFor) {
        if (!sentLog.claim(eventId, recipient, customerId)) {
            log.debug("Aviso {} del evento {} ya enviado", recipient, eventId);
            return;
        }
        // Contacto y correo, dentro del circuit breaker (BANK-73): con el circuito abierto ni se intenta
        boolean sent = circuit.call(() -> deliver(transferId, customerId, mailFor));
        if (!sent) {
            sentLog.markNotSent(eventId, recipient);
        }
    }

    // true si se mandó; false si no había a quién (cliente inexistente o sin correo), que no se reintenta
    private boolean deliver(String transferId, String customerId, Function<String, TransferMails.Mail> mailFor) {
        Optional<CustomerContacts.Contact> contact = contacts.find(customerId).filter(c -> !c.email().isBlank());
        if (contact.isEmpty()) {
            log.warn("Sin correo para el cliente {}: no se le avisa la transferencia {}", customerId, transferId);
            return false;
        }
        TransferMails.Mail content = mailFor.apply(contact.get().name());
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(contact.get().email());
        message.setSubject(content.subject());
        message.setText(content.text());
        mail.send(message);
        return true;
    }
}
