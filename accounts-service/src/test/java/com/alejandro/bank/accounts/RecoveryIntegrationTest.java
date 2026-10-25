package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.corebanking.CoreBanking;
import com.alejandro.bank.accounts.corebanking.CoreBankingEntry;
import com.alejandro.bank.accounts.domain.AccountNumber;
import com.alejandro.bank.accounts.domain.Transfer;
import com.alejandro.bank.accounts.service.TransferExecutor;
import com.alejandro.bank.accounts.service.TransferRecovery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BANK-79: el recuperador termina toda transferencia PENDING (timeout del core o caída a mitad de la SAGA) sin mover el
// dinero dos veces. Contra el mainframe emulado de verdad. Después de cada prueba: saldos + pendientes = lo que había
@TestPropertySource(properties = {
        // Las pendientes cuentan enseguida, y el tope de revisión es corto para poder probarlo
        "bank.saga.recovery.min-age=0s",
        "bank.saga.recovery.review-after=2s"})
class RecoveryIntegrationTest extends PostgresIntegrationTest {

    private static final BigDecimal TOTAL = new BigDecimal("2000.00");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransferRecovery recovery;

    @Autowired
    TransferExecutor executor;

    @Autowired
    CoreBanking coreBanking;

    UUID anaPen;
    UUID luisPen;
    String anaPenNumber = AccountNumber.of(950_001);
    String luisPenNumber = AccountNumber.of(950_002);

    @BeforeEach
    void accounts() throws Exception {
        jdbc.update("delete from outbox");
        jdbc.update("delete from movement");
        jdbc.update("delete from transfer");
        jdbc.update("delete from account");
        anaPen = insertAccount(ANA, anaPenNumber);
        luisPen = insertAccount(LUIS, luisPenNumber);
        HTTP.send(HttpRequest.newBuilder(URI.create(coreUrl() + "/__admin/requests")).DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    @AfterEach
    void theBankNeverCreatesNorLosesMoney() {
        BigDecimal balances = jdbc.queryForObject("select sum(balance) from account", BigDecimal.class);
        BigDecimal inTransit = jdbc.queryForObject("select coalesce(sum(amount), 0) from transfer where status = 'PENDING'", BigDecimal.class);
        assertThat(balances.add(inTransit)).as("saldos + pendientes").isEqualByComparingTo(TOTAL);
    }

    @Test
    void aSlowTransferThatEndedPendingIsCompletedByTheRecovery() throws Exception {
        UUID key = UUID.randomUUID();
        transfer(key, "100.99").andExpect(status().isAccepted());

        assertThat(recovery.recoverPending()).isEqualTo(1);

        assertThat(statusOf()).isEqualTo("COMPLETED");
        assertThat(balance(luisPen)).isEqualTo("1100.99");
        assertThat(events()).isEqualTo(1);
        // El reintento con la misma clave ahora ve la transferencia completada
        transfer(key, "100.99").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void aCrashAfterT1AndBeforeThePostingIsResolved() {
        // accounts-service se cayó después de debitar y antes de mandar el asiento. Límite del mock (BANK-76): sin
        // estado, su consulta dice "registrado" para un monto normal aunque nunca llegó; un core real diría "no existe"
        // y el recuperador reenviaría el asiento (ese camino lo prueba aCoreThatNeverReceivedIt…)
        executor.start(UUID.randomUUID(), "h", "oid", anaPen, luisPenNumber, new BigDecimal("50.00"));

        assertThat(recovery.recoverPending()).isEqualTo(1);

        assertThat(statusOf()).isEqualTo("COMPLETED");
        assertThat(balance(anaPen)).isEqualTo("950.00");
        assertThat(balance(luisPen)).isEqualTo("1050.00");
    }

    @Test
    void aCrashAfterARejectedPostingIsCompensated() {
        // Se cayó después de que el core rechazó el asiento y antes de T2
        TransferExecutor.Started started = executor.start(UUID.randomUUID(), "h", "oid", anaPen, luisPenNumber, new BigDecimal("60.13"));
        Transfer t = started.transfer();
        coreBanking.post(new CoreBankingEntry(t.getCoreOperation(), t.getCreatedAt(), started.fromAccountNumber(),
                started.toAccountNumber(), t.getCurrency(), t.getAmount()));

        assertThat(recovery.recoverPending()).isEqualTo(1);

        assertThat(statusOf()).isEqualTo("FAILED");
        assertThat(balance(anaPen)).isEqualTo("1000.00");
        assertThat(balance(luisPen)).isEqualTo("1000.00");
        assertThat(jdbc.queryForList("select type from movement where account_id = ? order by created_at", String.class, anaPen))
                .containsExactly("DEBIT", "REVERSAL");
    }

    @Test
    void aCoreThatNeverReceivedItGetsThePostingAgainAndIfItIsStillDownTheTransferWaitsAndIsFlaggedForReview() throws Exception {
        // .98: el core está caído y no registra nada; la consulta dice "no existe" y el reenvío vuelve a fallar
        executor.start(UUID.randomUUID(), "h", "oid", anaPen, luisPenNumber, new BigDecimal("70.98"));

        assertThat(recovery.recoverPending()).isZero();
        assertThat(coreRequests("/core/consultas")).isEqualTo(1);
        assertThat(coreRequests("/core/asientos")).isEqualTo(1);
        assertThat(statusOf()).isEqualTo("PENDING");
        assertThat(needsReview()).isFalse();

        // Pasado el tope (2 s en esta prueba): marcada para revisión, y el dinero NO se devuelve a ciegas
        Thread.sleep(2200);
        assertThat(recovery.recoverPending()).isZero();
        assertThat(needsReview()).isTrue();
        assertThat(statusOf()).isEqualTo("PENDING");
        assertThat(balance(anaPen)).isEqualTo("929.02");
    }

    @Test
    void twoRecoveriesAtOnceResolveEachPendingTransferOnceAndAskTheCoreOnce() throws Exception {
        for (int i = 1; i <= 5; i++) {
            executor.start(UUID.randomUUID(), "h", "oid", anaPen, luisPenNumber, new BigDecimal(i + "0.99"));
        }

        CountDownLatch start = new CountDownLatch(1);
        List<Integer> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> recoveries = List.of(
                    pool.submit(() -> { start.await(); return recovery.recoverPending(); }),
                    pool.submit(() -> { start.await(); return recovery.recoverPending(); }));
            start.countDown();
            for (Future<Integer> f : recoveries) {
                results.add(f.get());
            }
        }

        // Una hizo todo y la otra encontró el candado tomado (o nada que hacer si llegó después)
        assertThat(results.stream().filter(r -> r > 0).mapToInt(Integer::intValue).sum()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from transfer where status = 'COMPLETED'", Integer.class)).isEqualTo(5);
        assertThat(balance(luisPen)).isEqualTo("1154.95");
        assertThat(events()).isEqualTo(5);
        assertThat(coreRequests("/core/consultas")).isEqualTo(5);
    }

    // ---------------------------------------------------------------------------------------------------

    private ResultActions transfer(UUID key, String amount) throws Exception {
        return mvc.perform(post("/transfers")
                .header(HttpHeaders.AUTHORIZATION, CUSTOMER_TOKEN)
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":\"" + anaPen + "\",\"toAccountNumber\":\"" + luisPenNumber + "\",\"amount\":\"" + amount + "\"}"));
    }

    private String statusOf() {
        return jdbc.queryForObject("select status from transfer", String.class);
    }

    private boolean needsReview() {
        return jdbc.queryForObject("select needs_review from transfer", Boolean.class);
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

    private int events() {
        return jdbc.queryForObject("select count(*) from outbox", Integer.class);
    }

    // Cuántas veces el core recibió una petición en esa ruta (el journal de WireMock)
    private static int coreRequests(String url) throws Exception {
        String body = HTTP.send(HttpRequest.newBuilder(URI.create(coreUrl() + "/__admin/requests/count"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"method\":\"POST\",\"url\":\"" + url + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        Matcher count = Pattern.compile("\"count\"\\s*:\\s*(\\d+)").matcher(body);
        return count.find() ? Integer.parseInt(count.group(1)) : -1;
    }

    private static String coreUrl() {
        return "http://" + CORE_BANKING.getHost() + ":" + CORE_BANKING.getMappedPort(8080);
    }
}
