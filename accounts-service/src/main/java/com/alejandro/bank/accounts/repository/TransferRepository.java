package com.alejandro.bank.accounts.repository;

import com.alejandro.bank.accounts.domain.Transfer;
import com.alejandro.bank.accounts.domain.TransferStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    // La transferencia que ya se hizo con esta Idempotency-Key (UNIQUE en la base)
    Optional<Transfer> findByIdempotencyKey(UUID idempotencyKey);

    // El número de operación para el core (id-op, BANK-78)
    @Query(value = "select nextval('core_operation_seq')", nativeQuery = true)
    long nextCoreOperation();

    // La llave de la idempotencia de la SAGA (BANK-78, spike BANK-74 S1): la transferencia pasa de PENDING a su
    // estado final UNA sola vez. Devuelve 1 si este proceso ganó la transición (y solo entonces mueve dinero) y 0 si
    // ya estaba resuelta. Si dos procesos lo intentan a la vez, el segundo espera la fila y, al ver que ya no está
    // PENDING, no la toca. Sin esta condición, en el spike el destino recibió el dinero dos veces
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Transfer t set t.status = :status, t.failureReason = :reason, t.resolvedAt = :at
            where t.id = :id and t.status = com.alejandro.bank.accounts.domain.TransferStatus.PENDING
            """)
    int resolve(@Param("id") UUID id, @Param("status") TransferStatus status, @Param("reason") String reason,
                @Param("at") Instant at);

    // Las pendientes más viejas que "before", de la más antigua a la más nueva (BANK-79; índice transfer_pending)
    @Query("""
            select t from Transfer t
            where t.status = com.alejandro.bank.accounts.domain.TransferStatus.PENDING and t.createdAt < :before
            order by t.createdAt
            """)
    List<Transfer> findPendingCreatedBefore(@Param("before") Instant before, Pageable page);

    // Pendiente demasiado tiempo: alguien del banco tiene que revisarla con el core (BANK-79)
    @Modifying
    @Query("update Transfer t set t.needsReview = true where t.id = :id and t.needsReview = false")
    int markForReview(@Param("id") UUID id);
}
