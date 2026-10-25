package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.service.DepositService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BANK-55: depósito en ventanilla con PostgreSQL real; el saldo y sus movimientos siempre cuadran
class DepositIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DepositService depositService;

    UUID active;
    UUID blocked;

    @BeforeEach
    void accounts() {
        jdbc.update("delete from movement");
        jdbc.update("delete from transfer");
        jdbc.update("delete from account");
        active = insertAccount("ACTIVE", "1500.50");
        blocked = insertAccount("BLOCKED", "10.00");
    }

    @Test
    void depositAddsToTheBalanceAndRecordsACreditMovement() throws Exception {
        mvc.perform(deposit(active, ADMIN_TOKEN, "{\"amount\":\"150.00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value(active.toString()))
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.amount").value("150.00"))
                .andExpect(jsonPath("$.balanceAfter").value("1650.50"))
                .andExpect(jsonPath("$.transferId").doesNotExist());

        assertThat(balance(active)).isEqualTo("1650.50");
        assertThat(jdbc.queryForMap("select type, amount::text, balance_after::text, transfer_id from movement where account_id = ?", active))
                .containsEntry("type", "CREDIT")
                .containsEntry("amount", "150.00")
                .containsEntry("balance_after", "1650.50")
                .containsEntry("transfer_id", null);
    }

    @Test
    void amountAsJsonNumberKeepsItsExactValue() throws Exception {
        mvc.perform(deposit(active, ADMIN_TOKEN, "{\"amount\":0.10}"))
                .andExpect(status().isCreated());
        mvc.perform(deposit(active, ADMIN_TOKEN, "{\"amount\":0.20}"))
                .andExpect(status().isCreated())
                // 1500.50 + 0.10 + 0.20 exacto (con double, 0.1 + 0.2 = 0.30000000000000004)
                .andExpect(jsonPath("$.balanceAfter").value("1500.80"));
    }

    @Test
    void simultaneousDepositsAreNeverLost() throws Exception {
        int deposits = 20;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < deposits; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return depositService.deposit(UUID.randomUUID(), active, new BigDecimal("10.00"));
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get();
            }
        }

        // Sin candado, varios leerían el mismo saldo y se perderían depósitos
        assertThat(balance(active)).isEqualTo("1700.50");
        assertThat(jdbc.queryForObject("select count(distinct balance_after) from movement where account_id = ?",
                Integer.class, active)).isEqualTo(deposits);
        assertThat(jdbc.queryForObject("select max(balance_after)::text from movement where account_id = ?",
                String.class, active)).isEqualTo("1700.50");
    }

    @Test
    void movementsAlwaysAddUpToTheBalance() throws Exception {
        for (String amount : List.of("100.00", "0.01", "2500.99")) {
            mvc.perform(deposit(active, ADMIN_TOKEN, "{\"amount\":\"" + amount + "\"}")).andExpect(status().isCreated());
        }

        BigDecimal credited = jdbc.queryForObject("select sum(amount) from movement where account_id = ?", BigDecimal.class, active);
        assertThat(new BigDecimal("1500.50").add(credited)).isEqualByComparingTo(balance(active));
    }

    @Test
    void invalidAmountsAre400AndChangeNothing() throws Exception {
        for (String body : List.of("{}", "{\"amount\":0}", "{\"amount\":\"-5.00\"}", "{\"amount\":\"10.555\"}",
                "{\"amount\":\"1234567890123456.00\"}")) {
            mvc.perform(deposit(active, ADMIN_TOKEN, body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.errors[0].field").value("amount"));
        }
        mvc.perform(deposit(active, ADMIN_TOKEN, "{\"amount\":\"diez\"}"))
                .andExpect(status().isBadRequest());

        assertThat(balance(active)).isEqualTo("1500.50");
        assertThat(movements()).isZero();
    }

    @Test
    void unknownAccountIs404() throws Exception {
        mvc.perform(deposit(UUID.randomUUID(), ADMIN_TOKEN, "{\"amount\":\"10.00\"}"))
                .andExpect(status().isNotFound());

        assertThat(movements()).isZero();
    }

    @Test
    void blockedAccountIs422AndChangesNothing() throws Exception {
        mvc.perform(deposit(blocked, ADMIN_TOKEN, "{\"amount\":\"10.00\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Cuenta no activa"));

        assertThat(balance(blocked)).isEqualTo("10.00");
        assertThat(movements()).isZero();
    }

    @Test
    void customerCannotDeposit() throws Exception {
        mvc.perform(deposit(active, CUSTOMER_TOKEN, "{\"amount\":\"10.00\"}"))
                .andExpect(status().isForbidden());

        assertThat(balance(active)).isEqualTo("1500.50");
    }

    // Cada llamada es un depósito distinto (clave nueva); las pruebas de idempotencia usan depositWithKey
    private static RequestBuilder deposit(UUID accountId, String token, String body) {
        return depositWithKey(accountId, token, UUID.randomUUID(), body);
    }

    private static RequestBuilder depositWithKey(UUID accountId, String token, UUID key, String body) {
        return post("/accounts/" + accountId + "/deposits")
                .header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private UUID insertAccount(String status, String balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into account (id, number, customer_id, type, currency, balance, status, created_at, updated_at) "
                        + "values (?, ?, ?, 'AHORROS', 'PEN', ?, ?, now(), now())",
                id, "001-" + id.toString().substring(0, 10), ANA, new BigDecimal(balance), status);
        return id;
    }

    private String balance(UUID id) {
        return jdbc.queryForObject("select balance::text from account where id = ?", String.class, id);
    }

    private int movements() {
        return jdbc.queryForObject("select count(*) from movement", Integer.class);
    }
}
