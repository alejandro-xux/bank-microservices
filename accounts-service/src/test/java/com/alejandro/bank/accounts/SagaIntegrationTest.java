package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.domain.AccountNumber;
import com.alejandro.bank.accounts.service.TransferExecutor;
import com.alejandro.bank.contracts.transfers.v1.TransferFailed;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BANK-78: la SAGA de una transferencia contra el mainframe emulado de verdad (WireMock con las reglas del compose).
// Después de cada caso se comprueba la regla global del spike BANK-74 (S10): saldos + pendientes = lo que había. Cada
// cuenta por separado puede "cuadrar" con sus movimientos aunque el banco haya creado dinero de la nada
class SagaIntegrationTest extends PostgresIntegrationTest {

    // Dinero del banco al empezar cada prueba (dos cuentas PEN de 1000.00)
    private static final BigDecimal TOTAL = new BigDecimal("2000.00");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransferExecutor executor;

    UUID anaPen;
    UUID luisPen;
    String anaPenNumber = AccountNumber.of(940_001);
    String luisPenNumber = AccountNumber.of(940_002);

    @BeforeEach
    void accounts() {
        jdbc.update("delete from outbox");
        jdbc.update("delete from movement");
        jdbc.update("delete from transfer");
        jdbc.update("delete from account");
        anaPen = insertAccount(ANA, anaPenNumber);
        luisPen = insertAccount(LUIS, luisPenNumber);
    }

    @AfterEach
    void theBankNeverCreatesNorLosesMoney() {
        BigDecimal balances = jdbc.queryForObject("select sum(balance) from account", BigDecimal.class);
        BigDecimal inTransit = jdbc.queryForObject("select coalesce(sum(amount), 0) from transfer where status = 'PENDING'", BigDecimal.class);
        assertThat(balances.add(inTransit)).as("saldos + pendientes").isEqualByComparingTo(TOTAL);
    }

    @Test
    void whenTheCoreConfirmsTheTransferIsCompletedAndTheDestinationReceivesTheMoney() throws Exception {
        String body = transfer(UUID.randomUUID(), "100.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.resolvedAt").exists())
                .andReturn().getResponse().getContentAsString();

        assertThat(balance(anaPen)).isEqualTo("900.00");
        assertThat(balance(luisPen)).isEqualTo("1100.00");
        assertThat(movements(anaPen)).containsExactly("DEBIT");
        assertThat(movements(luisPen)).containsExactly("CREDIT");
        assertThat(events()).isEqualTo(1);
        mvc.perform(get("/transfers/" + JsonPath.read(body, "$.id")).header(HttpHeaders.AUTHORIZATION, CUSTOMER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void whenTheCoreRejectsItTheMoneyGoesBackWithAReversal() throws Exception {
        UUID key = UUID.randomUUID();
        String problem = transfer(key, "100.13")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Transferencia rechazada por el core bancario"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.transferStatus").value("FAILED"))
                .andExpect(jsonPath("$.reason").value("057"))
                .andReturn().getResponse().getContentAsString();

        // El débito no se borra: se compensa. El destino nunca recibió nada: no sale TransferCompleted, sale un
        // TransferFailed para avisarle al que envía (BANK-80), en la misma transacción que la compensación
        assertThat(balance(anaPen)).isEqualTo("1000.00");
        assertThat(balance(luisPen)).isEqualTo("1000.00");
        assertThat(movements(anaPen)).containsExactlyInAnyOrder("DEBIT", "REVERSAL");
        assertThat(movements(luisPen)).isEmpty();
        assertThat(jdbc.queryForList("select event_type from outbox", String.class)).containsExactly("TransferFailed");
        TransferFailed failed = TransferFailed.parseFrom(jdbc.queryForObject("select payload from outbox", byte[].class));
        assertThat(failed.getTransferId()).isEqualTo(JsonPath.read(problem, "$.transferId"));
        assertThat(failed.getFromAccountNumber()).isEqualTo(anaPenNumber);
        assertThat(failed.getFromCustomerId()).isEqualTo(ANA);
        assertThat(failed.getAmount()).isEqualTo("100.13");
        assertThat(failed.getReason()).isEqualTo("057");
        assertThat(jdbc.queryForObject("select message_key from outbox", String.class)).isEqualTo(anaPen.toString());
        assertThat(jdbc.queryForObject("select status from transfer", String.class)).isEqualTo("FAILED");
        // La reversa muestra en el historial a qué cuenta iba el dinero
        mvc.perform(get("/accounts/" + anaPen + "/movements").header(HttpHeaders.AUTHORIZATION, CUSTOMER_TOKEN))
                .andExpect(jsonPath("$.content[?(@.type == 'REVERSAL')].counterpartyAccount").value(masked(luisPenNumber)));
        // La clave no se libera: el reintento devuelve el mismo rechazo, sin mover dinero otra vez
        transfer(key, "100.13")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.transferId").value((String) JsonPath.read(problem, "$.transferId")));
        assertThat(movements(anaPen)).hasSize(2);
        assertThat(events()).isEqualTo(1);
    }

    @Test
    void whenTheCoreDoesNotAnswerInTimeTheTransferStaysPendingNotRejected() throws Exception {
        UUID key = UUID.randomUUID();
        long start = System.nanoTime();

        String body = transfer(key, "100.99")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        // El core sí lo registra (responde a los 5 s): compensarlo devolvería un dinero que ya se movió
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3500));
        assertThat(balance(anaPen)).isEqualTo("899.01");
        assertThat(balance(luisPen)).isEqualTo("1000.00");
        assertThat(movements(anaPen)).containsExactly("DEBIT");
        assertThat(events()).isZero();
        mvc.perform(get("/transfers/" + JsonPath.read(body, "$.id")).header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN))
                .andExpect(jsonPath("$.status").value("PENDING"));
        // Un reintento devuelve el estado de ahora
        transfer(key, "100.99").andExpect(status().isAccepted()).andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void whenTheCoreIsDownTheTransferStaysPending() throws Exception {
        transfer(UUID.randomUUID(), "100.98")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(balance(anaPen)).isEqualTo("899.02");
        assertThat(balance(luisPen)).isEqualTo("1000.00");
    }

    @Test
    void twoProcessesSettlingTheSamePendingTransferCreditItOnce() throws Exception {
        String body = transfer(UUID.randomUUID(), "100.99").andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID transferId = UUID.fromString(JsonPath.read(body, "$.id"));

        // Como el orquestador y el recuperador (BANK-79) cuando el core por fin confirma
        CountDownLatch start = new CountDownLatch(1);
        List<Boolean> won;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> settlers = List.of(
                    pool.submit(() -> { start.await(); return executor.complete(transferId); }),
                    pool.submit(() -> { start.await(); return executor.complete(transferId); }));
            start.countDown();
            won = List.of(settlers.get(0).get(), settlers.get(1).get());
        }

        assertThat(won).containsExactlyInAnyOrder(true, false);
        assertThat(balance(luisPen)).isEqualTo("1100.99");
        assertThat(movements(luisPen)).containsExactly("CREDIT");
        assertThat(events()).isEqualTo(1);
    }

    @Test
    void onlyTheOwnerOrTheAdminSeeATransferAndANonexistentOneLooksTheSame() throws Exception {
        String body = transfer(UUID.randomUUID(), "10.00").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        UUID missing = UUID.randomUUID();

        mvc.perform(get("/transfers/" + id).header(HttpHeaders.AUTHORIZATION, LUIS_TOKEN)).andExpect(status().isForbidden());
        mvc.perform(get("/transfers/" + missing).header(HttpHeaders.AUTHORIZATION, CUSTOMER_TOKEN)).andExpect(status().isForbidden());
        mvc.perform(get("/transfers/" + id).header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)).andExpect(status().isOk());
        mvc.perform(get("/transfers/" + missing).header(HttpHeaders.AUTHORIZATION, ADMIN_TOKEN)).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------------------------------------

    private ResultActions transfer(UUID key, String amount) throws Exception {
        return mvc.perform(post("/transfers")
                .header(HttpHeaders.AUTHORIZATION, CUSTOMER_TOKEN)
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":\"" + anaPen + "\",\"toAccountNumber\":\"" + luisPenNumber + "\",\"amount\":\"" + amount + "\"}"));
    }

    private UUID insertAccount(String customerId, String number) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into account (id, number, customer_id, type, currency, balance, status, created_at, updated_at) "
                + "values (?, ?, ?, 'AHORROS', 'PEN', 1000.00, 'ACTIVE', now(), now())", id, number, customerId);
        return id;
    }

    private String balance(UUID id) {
        return jdbc.queryForObject("select balance::text from account where id = ?", String.class, id);
    }

    private List<String> movements(UUID accountId) {
        return jdbc.queryForList("select type from movement where account_id = ? order by created_at", String.class, accountId);
    }

    private int events() {
        return jdbc.queryForObject("select count(*) from outbox", Integer.class);
    }

    private static String masked(String number) {
        return number.substring(0, 4) + "******" + number.substring(10);
    }
}
