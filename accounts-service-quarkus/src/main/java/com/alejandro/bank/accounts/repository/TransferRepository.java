package com.alejandro.bank.accounts.repository;

import com.alejandro.bank.accounts.domain.Transfer;
import com.alejandro.bank.accounts.domain.TransferStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Las mismas consultas que TransferRepository de Spring Data, con Panache
@ApplicationScoped
public class TransferRepository implements PanacheRepositoryBase<Transfer, UUID> {

    // La transferencia que ya se hizo con esta Idempotency-Key (UNIQUE en la base)
    public Optional<Transfer> findByIdempotencyKey(UUID idempotencyKey) {
        return find("idempotencyKey", idempotencyKey).firstResultOptional();
    }

    // El número de operación para el core (id-op, BANK-78)
    public long nextCoreOperation() {
        return ((Number) getEntityManager().createNativeQuery("select nextval('core_operation_seq')")
                .getSingleResult()).longValue();
    }

    // La llave de la idempotencia de la SAGA (spike BANK-74 S1): la transferencia pasa de PENDING a su estado final
    // UNA sola vez. Devuelve 1 si este proceso ganó la transición (y solo entonces mueve dinero) y 0 si ya estaba
    // resuelta. Si dos procesos lo intentan a la vez (aunque sean Spring y Quarkus), el segundo espera la fila y, al ver
    // que ya no está PENDING, no la toca
    public int resolve(UUID id, TransferStatus status, String reason, Instant at) {
        return update("status = :status, failureReason = :reason, resolvedAt = :at where id = :id and status = :pending",
                Parameters.with("status", status).and("reason", reason).and("at", at).and("id", id)
                        .and("pending", TransferStatus.PENDING));
    }

    // Las pendientes más viejas que "before", de la más antigua a la más nueva (BANK-79; índice transfer_pending)
    public List<Transfer> findPendingCreatedBefore(Instant before, int limit) {
        return find("status = ?1 and createdAt < ?2", Sort.by("createdAt"), TransferStatus.PENDING, before)
                .page(0, limit).list();
    }

    // Pendiente demasiado tiempo: alguien del banco tiene que revisarla con el core (BANK-79)
    public int markForReview(UUID id) {
        return update("needsReview = true where id = ?1 and needsReview = false", id);
    }
}
