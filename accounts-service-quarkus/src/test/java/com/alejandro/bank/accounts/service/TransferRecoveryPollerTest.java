package com.alejandro.bank.accounts.service;

import com.alejandro.bank.accounts.FakeCustomers;
import com.alejandro.bank.accounts.IntegrationTest;
import com.alejandro.bank.accounts.domain.AccountNumber;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// El proceso de fondo del recuperador (en %test no corre solo): una vuelta resuelve una pendiente que el core sí
// registró, igual que el recuperador llamado a mano en RecoveryTest
@QuarkusTest
class TransferRecoveryPollerTest extends IntegrationTest {

    @Inject
    TransferRecoveryPoller poller;

    @Inject
    TransferExecutor executor;

    @Test
    void oneRoundResolvesAPendingTransfer() {
        emptyBank();
        UUID ana = insertAccount(FakeCustomers.ANA, AccountNumber.of(970_001), "PEN", "1000.00", "ACTIVE");
        insertAccount(FakeCustomers.LUIS, AccountNumber.of(970_002), "PEN", "1000.00", "ACTIVE");
        executor.start(UUID.randomUUID(), "h", "oid", ana, AccountNumber.of(970_002), new BigDecimal("20.00"));

        poller.poll();

        assertThat(queryString("select status from transfer")).isEqualTo("COMPLETED");
    }
}
