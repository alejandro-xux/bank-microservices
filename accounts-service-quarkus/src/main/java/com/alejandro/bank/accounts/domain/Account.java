package com.alejandro.bank.accounts.domain;

import com.alejandro.bank.accounts.exception.AccountNotActiveException;
import com.alejandro.bank.accounts.exception.InsufficientFundsException;
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

// La misma tabla y las mismas reglas que accounts-service: sin setters, se crea con open() y el saldo solo cambia
// con deposit, receiveSettled y withdraw. Los campos son privados (no los públicos típicos de Panache) para que nadie
// pueda tocar el saldo sin pasar por esas reglas; las consultas viven en los repositorios
@Entity
@Table(name = "account")
public class Account {

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

    protected Account() {
    }

    // Apertura (BANK-53): saldo 0.00 y activa
    public static Account open(String customerId, AccountType type, Currency currency, String number) {
        Account account = new Account();
        account.customerId = customerId;
        account.type = type;
        account.currency = currency;
        account.number = number;
        account.balance = new BigDecimal("0.00");
        account.status = AccountStatus.ACTIVE;
        account.createdAt = Timestamps.now();
        account.updatedAt = account.createdAt;
        return account;
    }

    // Entrada de dinero (depósito, crédito de una transferencia): solo en una cuenta activa, con la fila bloqueada
    public void deposit(BigDecimal amount) {
        requireActive();
        balance = balance.add(amount);
        updatedAt = Timestamps.now();
    }

    // ¿Puede recibir una transferencia? Se pregunta antes de mandar el asiento al core (BANK-78)
    public void checkCanReceive() {
        requireActive();
    }

    // Entrada de dinero ya decidida (crédito confirmado por el core o reversa): no pide que la cuenta siga activa
    public void receiveSettled(BigDecimal amount) {
        balance = balance.add(amount);
        updatedAt = Timestamps.now();
    }

    // Salida de dinero: activa y con fondos suficientes. El CHECK (balance >= 0) de la base es la última barrera
    public void withdraw(BigDecimal amount) {
        requireActive();
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(id);
        }
        balance = balance.subtract(amount);
        updatedAt = Timestamps.now();
    }

    private void requireActive() {
        if (status != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException(id, status);
        }
    }

    public UUID getId() {
        return id;
    }

    public String getNumber() {
        return number;
    }

    public String getCustomerId() {
        return customerId;
    }

    public AccountType getType() {
        return type;
    }

    public Currency getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
