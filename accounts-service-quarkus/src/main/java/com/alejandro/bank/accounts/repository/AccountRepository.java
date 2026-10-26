package com.alejandro.bank.accounts.repository;

import com.alejandro.bank.accounts.domain.Account;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Las mismas consultas que AccountRepository de Spring Data, con Panache
@ApplicationScoped
public class AccountRepository implements PanacheRepositoryBase<Account, UUID> {

    // SELECT … FOR UPDATE: la fila queda bloqueada hasta el COMMIT (spike BANK-51). Solo dentro de una transacción
    public Optional<Account> lockById(UUID id) {
        return findByIdOptional(id, LockModeType.PESSIMISTIC_WRITE);
    }

    // Proyección (un valor, no la entidad): decidir ANTES de bloquear sin dejar la cuenta en el contexto de
    // persistencia, donde lockById encontraría una copia con el saldo leído antes del candado
    public Optional<String> findCustomerIdById(UUID id) {
        return getEntityManager()
                .createQuery("select a.customerId from Account a where a.id = :id", String.class)
                .setParameter("id", id)
                .getResultStream().findFirst();
    }

    // Las cuentas de un cliente, de la más antigua a la más nueva (usa idx_account_customer)
    public List<Account> findByCustomerIdOrderByCreatedAtAsc(String customerId) {
        return list("customerId = ?1 order by createdAt", customerId);
    }

    // Siguiente correlativo del número de cuenta: PostgreSQL nunca entrega el mismo dos veces
    public long nextAccountNumber() {
        return ((Number) getEntityManager().createNativeQuery("select nextval('account_number_seq')")
                .getSingleResult()).longValue();
    }
}
