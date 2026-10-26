package com.alejandro.bank.fraud.transfers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

// Los eventos ya revisados (V1__processed_event.sql). claim inserta el evento solo si no estaba: true la primera vez,
// false si es un duplicado (Kafka entrega al menos una vez). Corre dentro de la transacción del consumidor: si la
// revisión falla, el reclamo se deshace y el evento se vuelve a revisar
@ApplicationScoped
public class ProcessedEvents {

    private final AgroalDataSource dataSource;
    private final ObjectMapper json;

    ProcessedEvents(AgroalDataSource dataSource, ObjectMapper json) {
        this.dataSource = dataSource;
        this.json = json;
    }

    public boolean claim(UUID eventId, String eventType, EventSummary summary) {
        String sql = "INSERT INTO processed_event (event_id, event_type, summary, processed_at) "
                + "VALUES (?, ?, CAST(? AS jsonb), ?) ON CONFLICT (event_id) DO NOTHING";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setObject(1, eventId);
            insert.setString(2, eventType);
            insert.setString(3, json.writeValueAsString(summary));
            // Microsegundos, lo que guarda PostgreSQL (BANK-83)
            insert.setTimestamp(4, Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
            return insert.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo registrar el evento " + eventId, e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo escribir el resumen del evento " + eventId, e);
        }
    }
}
