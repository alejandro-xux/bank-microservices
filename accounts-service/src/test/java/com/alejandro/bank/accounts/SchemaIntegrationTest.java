package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.domain.Account;
import com.alejandro.bank.accounts.domain.Movement;
import com.alejandro.bank.accounts.domain.Transfer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Si el contexto arranca, Hibernate ya validó (ddl-auto: validate) que las entidades
// coinciden con las tablas que creó Flyway. Cada prueba se deshace al terminar (@Transactional)
@Transactional
class SchemaIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    @Test
    void flywayAppliedTheInitialMigration() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class))
                .contains("account", "transfer", "movement");
    }

    @Test
    void entitiesReadTheRowsOfEachTable() {
        UUID from = insertAccount("1000.00");
        UUID to = insertAccount("0.00");
        UUID transfer = insertTransfer(UUID.randomUUID(), from, to);
        UUID movement = UUID.randomUUID();
        jdbc.update("insert into movement (id, account_id, type, amount, balance_after, transfer_id, created_at) "
                + "values (?, ?, 'DEBIT', 80.00, 920.00, ?, now())", movement, from, transfer);

        Account account = entityManager.find(Account.class, from);
        assertThat(account.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(account.getType().name()).isEqualTo("AHORROS");
        assertThat(entityManager.find(Transfer.class, transfer).getToAccountId()).isEqualTo(to);
        assertThat(entityManager.find(Movement.class, movement).getBalanceAfter()).isEqualByComparingTo("920.00");
    }

    @Test
    void databaseRejectsNegativeBalance() {
        assertThatThrownBy(() -> insertAccount("-0.01"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("account_balance_check");
    }

    @Test
    void databaseRejectsTheSameIdempotencyKeyTwice() {
        UUID from = insertAccount("1000.00");
        UUID to = insertAccount("0.00");
        UUID key = UUID.randomUUID();
        insertTransfer(key, from, to);

        assertThatThrownBy(() -> insertTransfer(key, from, to))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("transfer_idempotency_key_key");
    }

    @Test
    void databaseRejectsATransferToTheSameAccount() {
        UUID account = insertAccount("1000.00");

        assertThatThrownBy(() -> insertTransfer(UUID.randomUUID(), account, account))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("transfer_check");
    }

    private UUID insertAccount(String balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into account (id, number, customer_id, type, currency, balance, status, created_at, updated_at) "
                        + "values (?, ?, 'c1', 'AHORROS', 'PEN', ?, 'ACTIVE', now(), now())",
                id, "001-" + id.toString().substring(0, 10), new BigDecimal(balance));
        return id;
    }

    private UUID insertTransfer(UUID idempotencyKey, UUID from, UUID to) {
        UUID id = UUID.randomUUID();
        // status sin valor por defecto desde V4 (BANK-78): toda transferencia nueva dice en qué estado está
        jdbc.update("insert into transfer (id, idempotency_key, request_hash, requested_by, from_account_id, "
                        + "to_account_id, amount, currency, created_at, status) values (?, ?, 'h', 'oid', ?, ?, 80.00, 'PEN', now(), 'COMPLETED')",
                id, idempotencyKey, from, to);
        return id;
    }
}
