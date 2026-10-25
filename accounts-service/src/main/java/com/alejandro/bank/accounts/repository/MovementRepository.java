package com.alejandro.bank.accounts.repository;

import com.alejandro.bank.accounts.domain.Movement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface MovementRepository extends JpaRepository<Movement, UUID> {

    // El depósito que ya se registró con esta Idempotency-Key (UNIQUE en la base, BANK-64)
    Optional<Movement> findByIdempotencyKey(UUID idempotencyKey);

    // Historial de una cuenta en UNA sentencia (BANK-57): la otra cuenta de cada transferencia sale
    // del mismo JOIN, sin una consulta por movimiento (N+1). LEFT JOIN para no perder los depósitos
    // (no tienen transferencia). DEBIT → la otra es el destino; CREDIT → el origen. El id desempata
    // fechas iguales, así cada movimiento cae en una sola página. Usa idx_movement_account_created.
    // La segunda sentencia (countQuery) da el total de páginas
    @Query(value = """
            select new com.alejandro.bank.accounts.repository.MovementLine(
                m.id, m.type, m.amount, m.balanceAfter, m.transferId, other.number, m.createdAt)
            from Movement m
            left join Transfer t on t.id = m.transferId
            left join Account other on other.id = case
                when m.type = com.alejandro.bank.accounts.domain.MovementType.DEBIT then t.toAccountId
                else t.fromAccountId end
            where m.accountId = :accountId
            order by m.createdAt desc, m.id desc
            """,
            countQuery = "select count(m) from Movement m where m.accountId = :accountId")
    Page<MovementLine> findHistory(@Param("accountId") UUID accountId, Pageable pageable);
}
