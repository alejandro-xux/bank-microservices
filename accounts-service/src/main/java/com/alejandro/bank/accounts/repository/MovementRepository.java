package com.alejandro.bank.accounts.repository;

import com.alejandro.bank.accounts.domain.Movement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MovementRepository extends JpaRepository<Movement, UUID> {
}
