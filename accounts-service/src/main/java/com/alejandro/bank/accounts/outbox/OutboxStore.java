package com.alejandro.bank.accounts.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

// La tabla outbox (V3, BANK-69) con SQL directo: el relay necesita el candado consultivo de PostgreSQL y
// un UPDATE por lote, que JPA no expresa. JdbcTemplate usa la misma conexión y la misma transacción que
// JPA, así que el INSERT de un evento queda dentro de la transacción de la transferencia
@Repository
@RequiredArgsConstructor
public class OutboxStore {

    // Número del candado consultivo del relay: uno solo para todo el sistema (spike BANK-66, K4)
    private static final long RELAY_LOCK = 69;

    private final JdbcTemplate jdbc;

    public record Message(UUID id, String eventType, String key, byte[] payload) {
    }

    // Se llama DENTRO de la transacción que mueve el dinero
    public void save(Message message) {
        jdbc.update("INSERT INTO outbox (id, event_type, message_key, payload) VALUES (?, ?, ?, ?)",
                message.id(), message.eventType(), message.key(), message.payload());
    }

    // Un solo relay activo en todo el sistema: el que no obtiene el candado se va sin esperar. El candado se
    // suelta solo al terminar la transacción (COMMIT o ROLLBACK), aunque el proceso muera.
    // No se usa FOR UPDATE SKIP LOCKED: con dos relays no duplica, pero desordena los eventos de una cuenta
    public boolean tryLockRelay() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, RELAY_LOCK));
    }

    // Las pendientes en el orden en que se movió el dinero (columna seq)
    public List<Message> pending(int limit) {
        return jdbc.query("SELECT id, event_type, message_key, payload FROM outbox "
                        + "WHERE published_at IS NULL ORDER BY seq LIMIT ?",
                (rs, row) -> new Message(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getString("message_key"), rs.getBytes("payload")),
                limit);
    }

    public void markPublished(List<UUID> ids) {
        jdbc.update("UPDATE outbox SET published_at = clock_timestamp() WHERE id = ANY (?)",
                (Object) ids.toArray(UUID[]::new));
    }
}
