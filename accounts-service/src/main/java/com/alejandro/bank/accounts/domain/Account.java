package com.alejandro.bank.accounts.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Tabla account (V1__init.sql). Sin setters: el saldo solo cambiará con las operaciones del dominio
// (abrir, depositar, transferir) que llegan en BANK-53 a BANK-56.
// Sin @Version: las transferencias bloquean las filas con FOR UPDATE (spike BANK-51)
@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    private UUID id;

    // 001-NNNNNNNNNN-D
    private String number;

    // Id del cliente en customers-service (otra base: no hay llave foránea)
    @Column(name = "customer_id")
    private String customerId;

    @Enumerated(EnumType.STRING)
    private AccountType type;

    @Enumerated(EnumType.STRING)
    private Currency currency;

    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    private AccountStatus status;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
