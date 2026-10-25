package com.alejandro.bank.notifications.transfers;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

// Qué avisos ya se mandaron (V1 processed_event, V2 sent_notification, BANK-72). Kafka entrega "al menos una vez":
// el mismo evento puede llegar dos veces, o a dos consumidores a la vez
@Repository
@RequiredArgsConstructor
class NotificationLog {

    private final JdbcTemplate jdbc;

    // El evento ya se procesó entero: se descarta sin volver a pedir contactos
    boolean isProcessed(UUID eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM processed_event WHERE event_id = ?)", Boolean.class, eventId));
    }

    void markProcessed(UUID eventId, String eventType) {
        jdbc.update("INSERT INTO processed_event (event_id, event_type) VALUES (?, ?) ON CONFLICT DO NOTHING",
                eventId, eventType);
    }

    // Reclama el aviso de ese destinatario DENTRO de la transacción del envío. false = ya lo tenía otro: si otro
    // consumidor lo reclamó y todavía no terminó, este INSERT espera en la clave primaria hasta que confirme
    // (entonces false) o se deshaga (entonces true, y lo manda este)
    boolean claim(UUID eventId, Recipient recipient, String customerId) {
        return jdbc.update("INSERT INTO sent_notification (event_id, recipient, customer_id, sent) VALUES (?, ?, ?, true) "
                + "ON CONFLICT DO NOTHING", eventId, recipient.name(), customerId) == 1;
    }

    // No había a quién mandarlo (cliente inexistente o sin correo): queda registrado y no se reintenta
    void markNotSent(UUID eventId, Recipient recipient) {
        jdbc.update("UPDATE sent_notification SET sent = false WHERE event_id = ? AND recipient = ?",
                eventId, recipient.name());
    }
}
