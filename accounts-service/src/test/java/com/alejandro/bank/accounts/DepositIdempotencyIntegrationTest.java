package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.service.DepositService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BANK-64: un depósito repetido (reintento o doble clic) entra una sola vez, con PostgreSQL real
class DepositIdempotencyIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DepositService depositService;

    UUID active;
    UUID other;
    UUID blocked;

    @BeforeEach
    void accounts() {
        jdbc.update("delete from movement");
        jdbc.update("delete from transfer");
        jdbc.update("delete from account");
        active = insertAccount("ACTIVE");
        other = insertAccount("ACTIVE");
        blocked = insertAccount("BLOCKED");
    }

    @Test
    void retryWithTheSameKeyReturnsTheSameMovementWithoutAddingAgain() throws Exception {
        UUID key = UUID.randomUUID();
        String first = deposit(active, key, "150.00")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();

        String retry = deposit(active, key, "150")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(retry).isEqualTo(first);
        assertThat(balance(active)).isEqualTo("150.00");
        assertThat(movements()).isEqualTo(1);
    }

    @Test
    void sameKeyWithAnotherAmountOrAccountIs409() throws Exception {
        UUID key = UUID.randomUUID();
        deposit(active, key, "150.00").andExpect(status().isCreated());

        deposit(active, key, "900.00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Idempotency-Key en conflicto"));
        deposit(other, key, "150.00")
                .andExpect(status().isConflict());

        assertThat(balance(active)).isEqualTo("150.00");
        assertThat(balance(other)).isEqualTo("0.00");
        assertThat(movements()).isEqualTo(1);
    }

    @Test
    void doubleClickWithTheSameKeyAddsOnce() throws Exception {
        UUID key = UUID.randomUUID();
        int clicks = 5;
        CountDownLatch start = new CountDownLatch(1);
        List<DepositService.Result> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<DepositService.Result>> futures = new ArrayList<>();
            for (int i = 0; i < clicks; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return depositService.deposit(key, active, new BigDecimal("100.00"));
                }));
            }
            start.countDown();
            for (Future<DepositService.Result> future : futures) {
                results.add(future.get());
            }
        }

        assertThat(balance(active)).isEqualTo("100.00");
        assertThat(movements()).isEqualTo(1);
        assertThat(results).extracting(result -> result.movement().getId()).containsOnly(results.getFirst().movement().getId());
        assertThat(results).filteredOn(DepositService.Result::replayed).hasSize(clicks - 1);
    }

    @Test
    void aRejectedDepositDoesNotUseUpTheKey() throws Exception {
        UUID key = UUID.randomUUID();
        deposit(blocked, key, "10.00").andExpect(status().isUnprocessableContent());
        jdbc.update("update account set status = 'ACTIVE' where id = ?", blocked);

        // Ya activa, el mismo intento (misma clave) ahora sí se registra
        deposit(blocked, key, "10.00")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertThat(balance(blocked)).isEqualTo("10.00");
    }

    @Test
    void anUnknownAccountDoesNotUseUpTheKeyEither() throws Exception {
        UUID key = UUID.randomUUID();
        deposit(UUID.randomUUID(), key, "10.00").andExpect(status().isNotFound());

        deposit(active, key, "10.00").andExpect(status().isCreated());
    }

    @Test
    void idempotencyKeyIsRequiredAndMustBeAUuid() throws Exception {
        mvc.perform(post("/accounts/" + active + "/deposits").header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"10.00\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/accounts/" + active + "/deposits").header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                        .header("Idempotency-Key", "abc").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"10.00\"}"))
                .andExpect(status().isBadRequest());

        assertThat(movements()).isZero();
    }

    private ResultActions deposit(UUID accountId, UUID key, String amount) throws Exception {
        return mvc.perform(post("/accounts/" + accountId + "/deposits")
                .header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"" + amount + "\"}"));
    }

    private UUID insertAccount(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into account (id, number, customer_id, type, currency, balance, status, created_at, updated_at) "
                        + "values (?, ?, ?, 'AHORROS', 'PEN', 0.00, ?, now(), now())",
                id, "001-" + id.toString().substring(0, 10), ANA, status);
        return id;
    }

    private String balance(UUID id) {
        return jdbc.queryForObject("select balance::text from account where id = ?", String.class, id);
    }

    private int movements() {
        return jdbc.queryForObject("select count(*) from movement", Integer.class);
    }
}
