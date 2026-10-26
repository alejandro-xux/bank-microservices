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

// Tabla transfer, la misma que accounts-service. Las cuentas van por id y no como relación JPA: leer una transferencia
// nunca dispara consultas extra a account. El estado solo cambia con TransferRepository.resolve (PENDING → X)
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

    // BANK-56: requestHash es la huella de los datos de la petición (origen, destino y monto)
    public static Transfer create(UUID idempotencyKey, String requestHash, String requestedBy,
                                  UUID fromAccountId, UUID toAccountId, BigDecimal amount, Currency currency,
                                  long coreOperation) {
        Transfer transfer = new Transfer();
        transfer.idempotencyKey = idempotencyKey;
        transfer.requestHash = requestHash;
        transfer.requestedBy = requestedBy;
        transfer.fromAccountId = fromAccountId;
        transfer.toAccountId = toAccountId;
        transfer.amount = amount;
        transfer.currency = currency;
        transfer.createdAt = Timestamps.now();
        transfer.status = TransferStatus.PENDING;
        transfer.coreOperation = coreOperation;
        return transfer;
    }

    // Misma clave y misma huella = reintento de esta transferencia; misma clave y otra huella = 409
    public boolean isSameRequest(String hash) {
        return requestHash.equals(hash);
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

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Long getCoreOperation() {
        return coreOperation;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public boolean isNeedsReview() {
        return needsReview;
    }
}
