package com.alejandro.bank.accounts.repository;

import com.alejandro.bank.accounts.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    // Las cuentas de un cliente, de la más antigua a la más nueva (usa idx_account_customer)
    List<Account> findByCustomerIdOrderByCreatedAtAsc(String customerId);

    // Siguiente correlativo del número de cuenta: PostgreSQL nunca entrega el mismo dos veces,
    // aunque lleguen varias aperturas a la vez (un número saltado por un error no se reutiliza)
    @Query(value = "select nextval('account_number_seq')", nativeQuery = true)
    long nextAccountNumber();
}
