package com.alejandro.bank.accounts.repository;

import com.alejandro.bank.accounts.domain.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    // La transferencia que ya se hizo con esta Idempotency-Key (UNIQUE en la base)
    Optional<Transfer> findByIdempotencyKey(UUID idempotencyKey);
}
