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

// Tabla movement (V1__init.sql): una línea del estado de cuenta, con el saldo que quedó
// para no recalcularlo al mostrar el historial
@Entity
@Table(name = "movement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Movement {

    // Lo genera Hibernate al guardar (un solo INSERT, como en Account)
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

    // Solo en un depósito (BANK-64): la Idempotency-Key del asesor y la huella de la petición.
    // En una transferencia la clave vive en transfer
    @Column(name = "idempotency_key")
    private UUID idempotencyKey;

    @Column(name = "request_hash")
    private String requestHash;

    @Column(name = "created_at")
    private Instant createdAt;

    // Depósito en ventanilla (BANK-55): entrada de dinero sin transferencia, con la Idempotency-Key que lo
    // hace único (BANK-64)
    public static Movement deposit(UUID accountId, BigDecimal amount, BigDecimal balanceAfter,
                                   UUID idempotencyKey, String requestHash) {
        Movement movement = of(accountId, MovementType.CREDIT, amount, balanceAfter, null);
        movement.idempotencyKey = idempotencyKey;
        movement.requestHash = requestHash;
        return movement;
    }

    // Las dos líneas de una transferencia (BANK-56): salida en el origen y entrada en el destino
    public static Movement debit(UUID accountId, BigDecimal amount, BigDecimal balanceAfter, UUID transferId) {
        return of(accountId, MovementType.DEBIT, amount, balanceAfter, transferId);
    }

    public static Movement credit(UUID accountId, BigDecimal amount, BigDecimal balanceAfter, UUID transferId) {
        return of(accountId, MovementType.CREDIT, amount, balanceAfter, transferId);
    }

    // Compensación de una transferencia rechazada por el core (BANK-78, S3): el dinero vuelve al origen con un
    // movimiento propio, así el historial cuenta lo que pasó (salió y volvió)
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
        movement.createdAt = Instant.now();
        return movement;
    }

    // Misma clave y misma huella = reintento de este depósito; misma clave y otra huella = 409
    public boolean isSameRequest(String hash) {
        return hash.equals(requestHash);
    }
}
