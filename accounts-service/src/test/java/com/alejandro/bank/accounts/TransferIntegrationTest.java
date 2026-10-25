package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.domain.AccountNumber;
import com.alejandro.bank.accounts.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BANK-56: transferencias con PostgreSQL real. El dinero total nunca cambia
class TransferIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransferService transferService;

    UUID anaPen;
    UUID anaUsd;
    UUID luisPen;
    UUID luisBlocked;
    String anaPenNumber = AccountNumber.of(900_001);
    String anaUsdNumber = AccountNumber.of(900_002);
    String luisPenNumber = AccountNumber.of(900_003);
    String luisBlockedNumber = AccountNumber.of(900_004);

    @BeforeEach
    void accounts() {
        jdbc.update("delete from movement");
        jdbc.update("delete from transfer");
        jdbc.update("delete from account");
        anaPen = insertAccount(ANA, anaPenNumber, "PEN", "1000.00", "ACTIVE");
        anaUsd = insertAccount(ANA, anaUsdNumber, "USD", "500.00", "ACTIVE");
        luisPen = insertAccount(LUIS, luisPenNumber, "PEN", "1000.00", "ACTIVE");
        luisBlocked = insertAccount(LUIS, luisBlockedNumber, "PEN", "50.00", "BLOCKED");
    }

    // ---- el camino feliz ----

    @Test
    void transferMovesTheMoneyWithTwoMovementsInOneTransaction() throws Exception {
        UUID key = UUID.randomUUID();

        transfer(CUSTOMER_TOKEN, key, anaPen, luisPenNumber, "100.00")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.fromAccountId").value(anaPen.toString()))
                .andExpect(jsonPath("$.toAccountId").value(luisPen.toString()))
                .andExpect(jsonPath("$.amount").value("100.00"))
                .andExpect(jsonPath("$.currency").value("PEN"));

        assertThat(balance(anaPen)).isEqualTo("900.00");
        assertThat(balance(luisPen)).isEqualTo("1100.00");
        assertThat(jdbc.queryForMap("select requested_by, amount::text from transfer where idempotency_key = ?", key))
                .containsEntry("requested_by", "4f8c2a1e")
                .containsEntry("amount", "100.00");
        assertThat(jdbc.queryForList("select type || ' ' || amount || ' ' || balance_after from movement "
                + "where transfer_id is not null order by type", String.class))
                .containsExactly("CREDIT 100.00 1100.00", "DEBIT 100.00 900.00");
    }

    @Test
    void adminCanTransferFromAnyCustomersAccount() throws Exception {
        transfer(ADMIN_TOKEN, UUID.randomUUID(), luisPen, anaPenNumber, "10.00")
                .andExpect(status().isCreated());

        assertThat(balance(luisPen)).isEqualTo("990.00");
    }

    // ---- idempotencia ----

    @Test
    void retryWithTheSameKeyReturnsTheSameTransferWithoutMovingMoneyAgain() throws Exception {
        UUID key = UUID.randomUUID();
        String first = transfer(CUSTOMER_TOKEN, key, anaPen, luisPenNumber, "100.00")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String retry = transfer(CUSTOMER_TOKEN, key, anaPen, luisPenNumber, "100")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(retry).isEqualTo(first);
        assertThat(balance(anaPen)).isEqualTo("900.00");
        assertThat(transfers()).isEqualTo(1);
    }

    @Test
    void sameKeyWithOtherDataIs409AndMovesNothing() throws Exception {
        UUID key = UUID.randomUUID();
        transfer(CUSTOMER_TOKEN, key, anaPen, luisPenNumber, "100.00").andExpect(status().isCreated());

        transfer(CUSTOMER_TOKEN, key, anaPen, luisPenNumber, "900.00")
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Idempotency-Key en conflicto"));

        assertThat(balance(anaPen)).isEqualTo("900.00");
        assertThat(transfers()).isEqualTo(1);
    }

    @Test
    void doubleClickWithTheSameKeyMovesTheMoneyOnce() throws Exception {
        UUID key = UUID.randomUUID();
        List<TransferService.Result> results = concurrently(2,
                i -> () -> transferService.transfer(key, anaPen, luisPenNumber, new BigDecimal("100.00"), admin()));

        assertThat(results).extracting(r -> r.transfer().getId()).containsOnly(results.getFirst().transfer().getId());
        assertThat(results).filteredOn(TransferService.Result::replayed).hasSize(1);
        assertThat(balance(anaPen)).isEqualTo("900.00");
        assertThat(transfers()).isEqualTo(1);
    }

    @Test
    void aRejectedTransferDoesNotUseUpTheKey() throws Exception {
        UUID key = UUID.randomUUID();
        transfer(CUSTOMER_TOKEN, key, anaPen, luisPenNumber, "1500.00")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Fondos insuficientes"));
        jdbc.update("update account set balance = 2000.00 where id = ?", anaPen);

        // Con fondos, el mismo intento (misma clave) ahora sí se hace
        transfer(CUSTOMER_TOKEN, key, anaPen, luisPenNumber, "1500.00")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertThat(balance(anaPen)).isEqualTo("500.00");
    }

    // ---- concurrencia ----

    @Test
    void twentySimultaneousCrossedTransfersKeepTheTotalAndNeverDeadlock() throws Exception {
        List<TransferService.Result> results = concurrently(20, i -> () -> i % 2 == 0
                ? transferService.transfer(UUID.randomUUID(), anaPen, luisPenNumber, new BigDecimal("10.00"), admin())
                : transferService.transfer(UUID.randomUUID(), luisPen, anaPenNumber, new BigDecimal("10.00"), admin()));

        // Todas terminaron (un deadlock habría lanzado una excepción y concurrently() fallaría)
        assertThat(results).hasSize(20);
        assertThat(balance(anaPen)).isEqualTo("1000.00");
        assertThat(balance(luisPen)).isEqualTo("1000.00");
        assertThat(totalMoney()).isEqualByComparingTo("2550.00");
        assertThat(jdbc.queryForObject("select count(*) from movement", Integer.class)).isEqualTo(40);
    }

    @Test
    void twentySimultaneousTransfersFromOneAccountNeverOverdraw() throws Exception {
        // 20 × 80.00 desde 1000.00: alcanzan 12 (960.00); las otras 8 se rechazan por fondos
        List<Object> outcomes = concurrently(20, i -> () -> {
            try {
                return transferService.transfer(UUID.randomUUID(), anaPen, luisPenNumber, new BigDecimal("80.00"), admin());
            } catch (RuntimeException ex) {
                return ex.getClass().getSimpleName();
            }
        });

        assertThat(outcomes).filteredOn(TransferService.Result.class::isInstance).hasSize(12);
        assertThat(outcomes).filteredOn("InsufficientFundsException"::equals).hasSize(8);
        assertThat(balance(anaPen)).isEqualTo("40.00");
        assertThat(totalMoney()).isEqualByComparingTo("2550.00");
    }

    // ---- reglas ----

    @Test
    void currencyMismatchIs422() throws Exception {
        transfer(CUSTOMER_TOKEN, UUID.randomUUID(), anaPen, anaUsdNumber, "10.00")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Transferencia rechazada"));

        assertUnchanged();
    }

    @Test
    void unknownDestinationIs422() throws Exception {
        transfer(CUSTOMER_TOKEN, UUID.randomUUID(), anaPen, AccountNumber.of(999_999), "10.00")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("La cuenta destino " + AccountNumber.of(999_999) + " no existe"));

        assertUnchanged();
    }

    @Test
    void blockedDestinationIs422() throws Exception {
        transfer(CUSTOMER_TOKEN, UUID.randomUUID(), anaPen, luisBlockedNumber, "10.00")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Cuenta no activa"));

        assertUnchanged();
    }

    @Test
    void sameAccountIs400() throws Exception {
        transfer(CUSTOMER_TOKEN, UUID.randomUUID(), anaPen, anaPenNumber, "10.00")
                .andExpect(status().isBadRequest());

        assertUnchanged();
    }

    @Test
    void mistypedDestinationNumberIs400WithoutLookingItUp() throws Exception {
        String typo = luisPenNumber.substring(0, luisPenNumber.length() - 1)
                + ((luisPenNumber.charAt(luisPenNumber.length() - 1) - '0' + 1) % 10);

        transfer(CUSTOMER_TOKEN, UUID.randomUUID(), anaPen, typo, "10.00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("toAccountNumber"));
    }

    @Test
    void invalidAmountsAre400() throws Exception {
        for (String amount : List.of("0", "-1.00", "10.555")) {
            transfer(CUSTOMER_TOKEN, UUID.randomUUID(), anaPen, luisPenNumber, amount)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("amount"));
        }
        assertUnchanged();
    }

    @Test
    void idempotencyKeyIsRequiredAndMustBeAUuid() throws Exception {
        String body = body(anaPen, luisPenNumber, "10.00");
        mvc.perform(post("/transfers").header(HttpHeaders.AUTHORIZATION, CUSTOMER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/transfers").header(HttpHeaders.AUTHORIZATION, CUSTOMER_TOKEN).header("Idempotency-Key", "abc")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        assertUnchanged();
    }

    // ---- quién puede ----

    @Test
    void customerCannotTransferFromAnotherCustomersAccountOrAMissingOne() throws Exception {
        String foreign = transfer(CUSTOMER_TOKEN, UUID.randomUUID(), luisPen, anaPenNumber, "10.00")
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        String missing = transfer(CUSTOMER_TOKEN, UUID.randomUUID(), UUID.randomUUID(), anaPenNumber, "10.00")
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertThat(foreign).isEqualTo(missing);
        assertUnchanged();
    }

    @Test
    void anotherCustomerReplayingAnasKeyGets403NotHerTransfer() throws Exception {
        UUID key = UUID.randomUUID();
        transfer(CUSTOMER_TOKEN, key, anaPen, luisPenNumber, "100.00").andExpect(status().isCreated());

        transfer(LUIS_TOKEN, key, anaPen, luisPenNumber, "100.00")
                .andExpect(status().isForbidden());
    }

    @Test
    void customersServiceDownIs503AndMovesNothing() throws Exception {
        transfer(CUSTOMERS_DOWN_TOKEN, UUID.randomUUID(), anaPen, luisPenNumber, "10.00")
                .andExpect(status().isServiceUnavailable());

        assertUnchanged();
    }

    // ---- utilidades ----

    private ResultActions transfer(String token, UUID key, UUID from, String toNumber, String amount) throws Exception {
        return mvc.perform(post("/transfers")
                .header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(from, toNumber, amount)));
    }

    private static String body(UUID from, String toNumber, String amount) {
        return "{\"fromAccountId\":\"" + from + "\",\"toAccountNumber\":\"" + toNumber + "\",\"amount\":\"" + amount + "\"}";
    }

    // Lanza n tareas a la vez (en hilos virtuales) y devuelve sus resultados
    private static <T> List<T> concurrently(int n, java.util.function.IntFunction<Callable<T>> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<T> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<T> callable = task.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return callable.call();
                }));
            }
            start.countDown();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
        }
        return results;
    }

    private static Authentication admin() {
        Jwt jwt = Jwt.withTokenValue("token-asesor").header("alg", "RS256").subject("7b2e9d4c").claim("oid", "7b2e9d4c")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
    }

    private void assertUnchanged() {
        assertThat(balance(anaPen)).isEqualTo("1000.00");
        assertThat(balance(luisPen)).isEqualTo("1000.00");
        assertThat(transfers()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from movement", Integer.class)).isZero();
    }

    private UUID insertAccount(String customerId, String number, String currency, String balance, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into account (id, number, customer_id, type, currency, balance, status, created_at, updated_at) "
                        + "values (?, ?, ?, 'AHORROS', ?, ?, ?, now(), now())",
                id, number, customerId, currency, new BigDecimal(balance), status);
        return id;
    }

    private String balance(UUID id) {
        return jdbc.queryForObject("select balance::text from account where id = ?", String.class, id);
    }

    // PEN + USD de prueba: 1000 + 500 + 1000 + 50
    private BigDecimal totalMoney() {
        return jdbc.queryForObject("select sum(balance) from account", BigDecimal.class);
    }

    private int transfers() {
        return jdbc.queryForObject("select count(*) from transfer", Integer.class);
    }
}
