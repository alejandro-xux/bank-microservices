package com.alejandro.bank.accounts.domain;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// La misma tabla que accounts-service (V1__init.sql). Con Panache los campos son públicos: Quarkus genera los getters
// y setters al compilar. Hibernate valida al arrancar que cada campo exista en la tabla (schema-management validate)
@Entity
@Table(name = "account")
public class Account extends PanacheEntityBase {

    @Id
    public UUID id;

    public String number;

    @Column(name = "customer_id")
    public String customerId;

    @Enumerated(EnumType.STRING)
    public AccountType type;

    @Enumerated(EnumType.STRING)
    public Currency currency;

    public BigDecimal balance;

    @Enumerated(EnumType.STRING)
    public AccountStatus status;

    @Column(name = "created_at")
    public Instant createdAt;

    @Column(name = "updated_at")
    public Instant updatedAt;

    // Las cuentas de un cliente, de la más antigua a la más nueva (como findByCustomerIdOrderByCreatedAtAsc)
    public static List<Account> ofCustomer(String customerId) {
        return list("customerId = ?1 order by createdAt", customerId);
    }
}
