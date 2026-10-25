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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Tabla account (V1__init.sql). Sin setters: se crea con open() y el saldo solo cambia con las
// operaciones del dominio: deposit y withdraw (depósitos BANK-55, transferencias BANK-56).
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
        account.createdAt = Timestamps.now();
        account.updatedAt = account.createdAt;
        return account;
    }

    // Entrada de dinero (depósito BANK-55, crédito de una transferencia BANK-56): solo en una cuenta activa.
    // Se llama con la fila bloqueada (FOR UPDATE) dentro de una transacción; JPA guarda el saldo nuevo
    // al hacer commit (la entidad está gestionada)
    public void deposit(BigDecimal amount) {
        requireActive();
        balance = balance.add(amount);
        updatedAt = Timestamps.now();
    }

    // ¿Puede recibir una transferencia? (BANK-78): se pregunta en T1, antes de mandar el asiento al core
    public void checkCanReceive() {
        requireActive();
    }

    // Entrada de dinero que ya está decidida (BANK-78): el crédito de una transferencia que el core confirmó, o la
    // reversa de una que rechazó. No pide que la cuenta siga activa: el dinero ya salió del otro lado y el core ya
    // registró (o anuló) el asiento; si la cuenta se bloqueó mientras tanto, igual tiene que recibirlo
    public void receiveSettled(BigDecimal amount) {
        balance = balance.add(amount);
        updatedAt = Timestamps.now();
    }

    // Salida de dinero (débito de una transferencia, BANK-56): activa y con fondos suficientes.
    // El CHECK (balance >= 0) de la base es la última barrera si esta regla fallara
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
}
