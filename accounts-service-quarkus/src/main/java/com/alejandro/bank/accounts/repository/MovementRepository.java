package com.alejandro.bank.accounts.repository;

import com.alejandro.bank.accounts.domain.Movement;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class MovementRepository implements PanacheRepositoryBase<Movement, UUID> {

    // Una página del historial: las líneas y el total, como la Page de Spring Data
    public record HistoryPage(List<MovementLine> lines, int page, int size, long totalElements) {
        public int totalPages() {
            return (int) ((totalElements + size - 1) / size);
        }
    }

    // El depósito que ya se registró con esta Idempotency-Key (UNIQUE en la base, BANK-64)
    public Optional<Movement> findByIdempotencyKey(UUID idempotencyKey) {
        return find("idempotencyKey", idempotencyKey).firstResultOptional();
    }

    // La misma consulta que accounts-service (BANK-57): UNA sentencia para la página, con la otra cuenta de cada
    // transferencia en el mismo JOIN (sin N+1), y otra para el total. El id desempata fechas iguales
    public HistoryPage findHistory(UUID accountId, int page, int size) {
        List<MovementLine> lines = getEntityManager().createQuery("""
                        select new com.alejandro.bank.accounts.repository.MovementLine(
                            m.id, m.type, m.amount, m.balanceAfter, m.transferId, other.number, m.createdAt)
                        from Movement m
                        left join Transfer t on t.id = m.transferId
                        left join Account other on other.id = case
                            when m.type in (com.alejandro.bank.accounts.domain.MovementType.DEBIT,
                                            com.alejandro.bank.accounts.domain.MovementType.REVERSAL) then t.toAccountId
                            else t.fromAccountId end
                        where m.accountId = :accountId
                        order by m.createdAt desc, m.id desc
                        """, MovementLine.class)
                .setParameter("accountId", accountId)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();
        long total = count("accountId", accountId);
        return new HistoryPage(lines, page, size, total);
    }
}
