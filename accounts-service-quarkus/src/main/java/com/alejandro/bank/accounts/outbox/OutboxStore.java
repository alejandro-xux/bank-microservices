package com.alejandro.bank.accounts.outbox;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

// La tabla outbox (V3, BANK-69) con SQL nativo, como OutboxStore de accounts-service: el relay necesita el candado
// consultivo de PostgreSQL y un UPDATE por lote. Va por el mismo EntityManager que las entidades, así que el INSERT
// de un evento queda dentro de la transacción de la transferencia
@ApplicationScoped
public class OutboxStore {

    // Número del candado consultivo del relay: el mismo que accounts-service, uno solo para todo el sistema
    private static final long RELAY_LOCK = 69;

    @Inject
    EntityManager em;

    public record Message(UUID id, String eventType, String key, byte[] payload) {

        // Un record compara un arreglo por referencia, no por contenido: dos mensajes con los mismos bytes serían
        // distintos. Y su toString mostraría los bytes del evento en un log: solo el largo
        @Override
        public boolean equals(Object other) {
            return other instanceof Message m && id.equals(m.id) && eventType.equals(m.eventType)
                    && key.equals(m.key) && Arrays.equals(payload, m.payload);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(id, eventType, key) + Arrays.hashCode(payload);
        }

        @Override
        public String toString() {
            return "Message[id=" + id + ", eventType=" + eventType + ", key=" + key + ", payload=" + payload.length + " bytes]";
        }
    }

    // Se llama DENTRO de la transacción que mueve el dinero
    public void save(Message message) {
        em.createNativeQuery("INSERT INTO outbox (id, event_type, message_key, payload) VALUES (?1, ?2, ?3, ?4)")
                .setParameter(1, message.id())
                .setParameter(2, message.eventType())
                .setParameter(3, message.key())
                .setParameter(4, message.payload())
                .executeUpdate();
    }

    // Un solo relay activo en todo el sistema (también entre las dos versiones): el que no obtiene el candado se va
    // sin esperar. Se suelta solo al terminar la transacción, aunque el proceso muera
    public boolean tryLockRelay() {
        return Boolean.TRUE.equals(em.createNativeQuery("SELECT pg_try_advisory_xact_lock(?1)")
                .setParameter(1, RELAY_LOCK).getSingleResult());
    }

    // Las pendientes en el orden en que se movió el dinero (columna seq)
    public List<Message> pending(int limit) {
        List<?> rows = em.createNativeQuery("SELECT id, event_type, message_key, payload FROM outbox "
                        + "WHERE published_at IS NULL ORDER BY seq LIMIT ?1")
                .setParameter(1, limit).getResultList();
        return rows.stream().map(Object[].class::cast)
                .map(row -> new Message((UUID) row[0], (String) row[1], (String) row[2], (byte[]) row[3]))
                .toList();
    }

    public void markPublished(List<UUID> ids) {
        em.createNativeQuery("UPDATE outbox SET published_at = clock_timestamp() WHERE id IN (?1)")
                .setParameter(1, ids).executeUpdate();
    }

    // Borra hasta "limit" filas publicadas antes de "before" (BANK-81). Una pendiente tiene published_at NULL y
    // NULL < x nunca es verdadero: este filtro garantiza que un evento sin publicar no se borre
    public int deletePublishedBefore(Instant before, int limit) {
        return em.createNativeQuery("DELETE FROM outbox WHERE id IN "
                        + "(SELECT id FROM outbox WHERE published_at < ?1 ORDER BY published_at LIMIT ?2)")
                .setParameter(1, before).setParameter(2, limit).executeUpdate();
    }
}
