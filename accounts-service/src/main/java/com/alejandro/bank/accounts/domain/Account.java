package com.alejandro.bank.accounts.domain;

import com.alejandro.bank.accounts.exception.AccountNotActiveException;
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

// Tabla account (V1__init.sql). Sin setters: se crea con open() y el saldo solo cambia con las
// operaciones del dominio (deposit; las transferencias llegan en BANK-56).
// Sin @Version: las transferencias bloquean las filas con FOR UPDATE (spike BANK-51)
@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    // Lo genera Hibernate al guardar: con id vacío, save() sabe que es nueva y hace un solo INSERT
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
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

    // Apertura (BANK-53): saldo 0.00 y activa
    public static Account open(String customerId, AccountType type, Currency currency, String number) {
        Account account = new Account();
        account.customerId = customerId;
        account.type = type;
        account.currency = currency;
        account.number = number;
        account.balance = new BigDecimal("0.00");
        account.status = AccountStatus.ACTIVE;
        account.createdAt = Instant.now();
        account.updatedAt = account.createdAt;
        return account;
    }

    // Depósito (BANK-55): solo en una cuenta activa. Se llama con la fila bloqueada (FOR UPDATE) dentro
    // de una transacción; JPA guarda el saldo nuevo al hacer commit (la entidad está gestionada)
    public void deposit(BigDecimal amount) {
        if (status != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(id, status);
        }
        balance = balance.add(amount);
        updatedAt = Instant.now();
    }
}
