package com.alejandro.bank.accounts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Tabla transfer. Aquí se mapea para el historial de movimientos (la otra cuenta sale de la transferencia); la
// SAGA que la crea y la resuelve llega en BANK-101. Las cuentas van por id, no como relación JPA
@Entity
@Table(name = "transfer")
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key")
    private UUID idempotencyKey;

    @Column(name = "request_hash")
    private String requestHash;

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

    @Enumerated(EnumType.STRING)
    private TransferStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "core_operation")
    private Long coreOperation;

    @Column(name = "needs_review")
    private boolean needsReview;

    protected Transfer() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getFromAccountId() {
        return fromAccountId;
    }

    public UUID getToAccountId() {
        return toAccountId;
    }
}
