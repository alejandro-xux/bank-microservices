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

// Tabla movement: una línea del estado de cuenta, con el saldo que quedó (igual que en accounts-service)
@Entity
@Table(name = "movement")
public class Movement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id")
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    private MovementType type;

    private BigDecimal amount;

    @Column(name = "balance_after")
    private BigDecimal balanceAfter;

    // Vacío en un depósito de ventanilla
    @Column(name = "transfer_id")
    private UUID transferId;

    // Solo en un depósito (BANK-64): la Idempotency-Key del asesor y la huella de la petición
    @Column(name = "idempotency_key")
    private UUID idempotencyKey;

    @Column(name = "request_hash")
    private String requestHash;

    @Column(name = "created_at")
    private Instant createdAt;

    protected Movement() {
    }

    public static Movement deposit(UUID accountId, BigDecimal amount, BigDecimal balanceAfter,
                                   UUID idempotencyKey, String requestHash) {
        Movement movement = of(accountId, MovementType.CREDIT, amount, balanceAfter, null);
        movement.idempotencyKey = idempotencyKey;
        movement.requestHash = requestHash;
        return movement;
    }

    public static Movement debit(UUID accountId, BigDecimal amount, BigDecimal balanceAfter, UUID transferId) {
        return of(accountId, MovementType.DEBIT, amount, balanceAfter, transferId);
    }

    public static Movement credit(UUID accountId, BigDecimal amount, BigDecimal balanceAfter, UUID transferId) {
        return of(accountId, MovementType.CREDIT, amount, balanceAfter, transferId);
    }

    // Compensación de una transferencia rechazada por el core (BANK-78): el dinero vuelve con un movimiento propio
    public static Movement reversal(UUID accountId, BigDecimal amount, BigDecimal balanceAfter, UUID transferId) {
        return of(accountId, MovementType.REVERSAL, amount, balanceAfter, transferId);
    }

    private static Movement of(UUID accountId, MovementType type, BigDecimal amount, BigDecimal balanceAfter,
                               UUID transferId) {
        Movement movement = new Movement();
        movement.accountId = accountId;
        movement.type = type;
        movement.amount = amount;
        movement.balanceAfter = balanceAfter;
        movement.transferId = transferId;
        movement.createdAt = Timestamps.now();
        return movement;
    }

    // Misma clave y misma huella = reintento de este depósito; misma clave y otra huella = 409
    public boolean isSameRequest(String hash) {
        return hash.equals(requestHash);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public MovementType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
