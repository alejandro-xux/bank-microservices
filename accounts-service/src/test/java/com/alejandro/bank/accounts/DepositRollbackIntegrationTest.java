package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.repository.MovementRepository;
import com.alejandro.bank.accounts.service.DepositService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

// Si guardar el movimiento falla, el saldo tampoco cambia: las dos escrituras van en la misma transacción
class DepositRollbackIntegrationTest extends PostgresIntegrationTest {

    @MockitoSpyBean
    MovementRepository movementRepository;

    @Autowired
    DepositService depositService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void ifTheMovementFailsTheBalanceIsRolledBack() {
        UUID account = UUID.randomUUID();
        jdbc.update("insert into account (id, number, customer_id, type, currency, balance, status, created_at, updated_at) "
                        + "values (?, ?, ?, 'AHORROS', 'PEN', 1500.50, 'ACTIVE', now(), now())",
                account, "001-" + account.toString().substring(0, 10), ANA);
        doThrow(new IllegalStateException("falla simulada al guardar el movimiento"))
                .when(movementRepository).saveAndFlush(any());

        assertThatThrownBy(() -> depositService.deposit(UUID.randomUUID(), account, new BigDecimal("150.00")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("select balance::text from account where id = ?", String.class, account))
                .isEqualTo("1500.50");
        assertThat(jdbc.queryForObject("select count(*) from movement where account_id = ?", Integer.class, account))
                .isZero();
    }
}
