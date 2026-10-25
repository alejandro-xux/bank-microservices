package com.alejandro.bank.accounts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Tabla transfer (V1__init.sql). Las cuentas van por id y no como relación JPA:
// leer una transferencia nunca dispara consultas extra a account
@Entity
@Table(name = "transfer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    // Lo genera Hibernate al guardar
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Header Idempotency-Key del cliente: UNIQUE en la base
    @Column(name = "idempotency_key")
    private UUID idempotencyKey;

    @Column(name = "request_hash")
    private String requestHash;

    // oid del usuario que la pidió
    @Column(name = "requested_by")
    private String requestedBy;

    @Column(name = "from_account_id")
    private UUID fromAccountId;

    @Column(name = "to_account_id")
    private UUID toAccountId;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private Currency currency;

    @Column(name = "created_at")
    private Instant createdAt;

    // BANK-56: requestHash es la huella de los datos de la petición (origen, destino y monto)
    public static Transfer create(UUID idempotencyKey, String requestHash, String requestedBy,
                                  UUID fromAccountId, UUID toAccountId, BigDecimal amount, Currency currency) {
        Transfer transfer = new Transfer();
        transfer.idempotencyKey = idempotencyKey;
        transfer.requestHash = requestHash;
        transfer.requestedBy = requestedBy;
        transfer.fromAccountId = fromAccountId;
        transfer.toAccountId = toAccountId;
        transfer.amount = amount;
        transfer.currency = currency;
        transfer.createdAt = Instant.now();
        return transfer;
    }

    // Misma clave y misma huella = reintento de esta transferencia; misma clave y otra huella = 409
    public boolean isSameRequest(String hash) {
        return requestHash.equals(hash);
    }
}
