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

    @Column(name = "created_at")
    private Instant createdAt;

    // Entrada de dinero sin transferencia (depósito en ventanilla, BANK-55)
    public static Movement credit(UUID accountId, BigDecimal amount, BigDecimal balanceAfter) {
        Movement movement = new Movement();
        movement.accountId = accountId;
        movement.type = MovementType.CREDIT;
        movement.amount = amount;
        movement.balanceAfter = balanceAfter;
        movement.createdAt = Instant.now();
        return movement;
    }
}
