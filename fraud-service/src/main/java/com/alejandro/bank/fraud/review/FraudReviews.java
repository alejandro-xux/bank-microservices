package com.alejandro.bank.fraud.review;

import com.alejandro.bank.fraud.rules.AccountHistory;
import com.alejandro.bank.fraud.rules.ReviewedTransfer;
import com.alejandro.bank.fraud.rules.SuspicionRule;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

// Las revisiones guardadas (V2__fraud_review.sql): la historia de la cuenta que miran las reglas y el registro de
// cada evaluación. Corre dentro de la transacción del consumidor, como ProcessedEvents
@ApplicationScoped
public class FraudReviews {

    private static final String HISTORY = """
            SELECT
              (SELECT count(*) FROM fraud_review
                WHERE from_account_id = ? AND completed_at > ? AND completed_at <= ?),
              (SELECT count(*) FROM fraud_review WHERE from_account_id = ? AND currency = ?),
              (SELECT avg(amount) FROM fraud_review WHERE from_account_id = ? AND currency = ?),
              EXISTS (SELECT 1 FROM fraud_review WHERE from_account_id = ? AND to_account_id = ?)
            """;

    private final AgroalDataSource dataSource;

    FraudReviews(AgroalDataSource dataSource) {
        this.dataSource = dataSource;
    }

    // Lo que se sabe de la cuenta antes de esta transferencia. La ventana de frecuencia termina en el momento de la
    // transferencia (el del evento), no en el de la revisión
    public AccountHistory historyBefore(ReviewedTransfer transfer, Duration velocityWindow) {
        UUID from = UUID.fromString(transfer.fromAccountId());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(HISTORY)) {
            select.setObject(1, from);
            select.setTimestamp(2, Timestamp.from(transfer.completedAt().minus(velocityWindow)));
            select.setTimestamp(3, Timestamp.from(transfer.completedAt()));
            select.setObject(4, from);
            select.setString(5, transfer.currency());
            select.setObject(6, from);
            select.setString(7, transfer.currency());
            select.setObject(8, from);
            select.setObject(9, UUID.fromString(transfer.toAccountId()));
            try (ResultSet row = select.executeQuery()) {
                row.next();
                BigDecimal average = row.getBigDecimal(3);
                return new AccountHistory(row.getInt(1), row.getInt(2), average, row.getBoolean(4));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer la historia de la cuenta " + from, e);
        }
    }

    public void record(UUID eventId, ReviewedTransfer transfer, Set<SuspicionRule> rules) {
        String sql = "INSERT INTO fraud_review (event_id, transfer_id, from_account_id, to_account_id, amount, currency, "
                + "completed_at, rules, suspicious, reviewed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setObject(1, eventId);
            insert.setObject(2, UUID.fromString(transfer.transferId()));
            insert.setObject(3, UUID.fromString(transfer.fromAccountId()));
            insert.setObject(4, UUID.fromString(transfer.toAccountId()));
            insert.setBigDecimal(5, transfer.amount());
            insert.setString(6, transfer.currency());
            insert.setTimestamp(7, Timestamp.from(transfer.completedAt()));
            insert.setArray(8, connection.createArrayOf("varchar", rules.stream().map(Enum::name).toArray()));
            insert.setBoolean(9, !rules.isEmpty());
            // Microsegundos, lo que guarda PostgreSQL (BANK-83)
            insert.setTimestamp(10, Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo registrar la revisión del evento " + eventId, e);
        }
    }
}
