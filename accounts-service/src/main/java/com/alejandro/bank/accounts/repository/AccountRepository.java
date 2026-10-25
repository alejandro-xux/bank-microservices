package com.alejandro.bank.accounts.repository;

import com.alejandro.bank.accounts.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    // SELECT … FOR UPDATE: bloquea la fila hasta el COMMIT. Otra transacción que quiera tocar el mismo
    // saldo espera su turno, así nadie lee un saldo viejo y lo pisa (lost update, spike BANK-51).
    // Solo dentro de una transacción
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> lockById(@Param("id") UUID id);

    // Las cuentas de un cliente, de la más antigua a la más nueva (usa idx_account_customer)
    List<Account> findByCustomerIdOrderByCreatedAtAsc(String customerId);

    // Siguiente correlativo del número de cuenta: PostgreSQL nunca entrega el mismo dos veces,
    // aunque lleguen varias aperturas a la vez (un número saltado por un error no se reutiliza)
    @Query(value = "select nextval('account_number_seq')", nativeQuery = true)
    long nextAccountNumber();
}
