package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.corebanking.CoreBanking;
import com.alejandro.bank.accounts.corebanking.CoreBankingEntry;
import com.alejandro.bank.accounts.domain.AccountNumber;
import com.alejandro.bank.accounts.domain.Transfer;
import com.alejandro.bank.accounts.service.TransferExecutor;
import com.alejandro.bank.accounts.service.TransferRecovery;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
import static org.hamcrest.Matchers.equalTo;

// El recuperador termina toda transferencia PENDING (timeout del core o caída a mitad de la SAGA) sin mover el dinero
// dos veces: los mismos casos que RecoveryIntegrationTest de accounts-service (BANK-79), sin los eventos del outbox
// (BANK-102). min-age=0s y review-after=2s vienen del perfil %test. Después de cada prueba: saldos + pendientes = lo
// que había
@QuarkusTest
class RecoveryTest extends IntegrationTest {

    private static final BigDecimal TOTAL = new BigDecimal("2000.00");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Inject
    TransferRecovery recovery;

    @Inject
    TransferExecutor executor;

    @Inject
    CoreBanking coreBanking;

    UUID anaPen;
    UUID luisPen;
    String anaPenNumber = AccountNumber.of(950_001);
    String luisPenNumber = AccountNumber.of(950_002);

    @BeforeEach
    void accounts() throws IOException, InterruptedException {
        emptyBank();
        anaPen = insertAccount(FakeCustomers.ANA, anaPenNumber, "PEN", "1000.00", "ACTIVE");
        luisPen = insertAccount(FakeCustomers.LUIS, luisPenNumber, "PEN", "1000.00", "ACTIVE");
        // El journal de WireMock vacío: se cuentan las peticiones de cada prueba
        HTTP.send(HttpRequest.newBuilder(URI.create(AccountsTestResource.coreBankingUrl() + "/__admin/requests")).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    @AfterEach
    void theBankNeverCreatesNorLosesMoney() {
        BigDecimal balances = new BigDecimal(queryString("select sum(balance)::text from account"));
        BigDecimal inTransit = new BigDecimal(queryString("select coalesce(sum(amount), 0)::text from transfer where status = 'PENDING'"));
        assertThat(balances.add(inTransit)).as("saldos + pendientes").isEqualByComparingTo(TOTAL);
    }

    @Test
    void aSlowTransferThatEndedPendingIsCompletedByTheRecovery() {
        UUID key = UUID.randomUUID();
        transfer(key, "100.99").statusCode(202);

        assertThat(recovery.recoverPending()).isEqualTo(1);

        assertThat(statusOf()).isEqualTo("COMPLETED");
        assertThat(balance(luisPen)).isEqualTo("1100.99");
        // El reintento con la misma clave ahora ve la transferencia completada
        transfer(key, "100.99").statusCode(201)
                .body("status", equalTo("COMPLETED"))
                .header("Idempotent-Replayed", equalTo("true"));
    }

    @Test
    void aCrashAfterT1AndBeforeThePostingIsResolved() throws Exception {
        // El servicio se cayó después de debitar y antes de mandar el asiento. El core no lo recibió (el mock recuerda
        // por id-op), así que la consulta dice "no existe" y el recuperador reenvía el mismo asiento
        executor.start(UUID.randomUUID(), "h", "oid", anaPen, luisPenNumber, new BigDecimal("50.00"));

        assertThat(recovery.recoverPending()).isEqualTo(1);

        assertThat(coreRequests("/core/consultas")).isEqualTo(1);
        assertThat(coreRequests("/core/asientos")).isEqualTo(1);
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
        assertThat(queryString("select string_agg(type, ', ' order by created_at, type) from movement where account_id = ?", anaPen))
                .isEqualTo("DEBIT, REVERSAL");
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

        // Pasado el tope (2 s en las pruebas): marcada para revisión, y el dinero NO se devuelve a ciegas
        Thread.sleep(2200);
        assertThat(recovery.recoverPending()).isZero();
        assertThat(needsReview()).isTrue();
        assertThat(statusOf()).isEqualTo("PENDING");
        assertThat(balance(anaPen)).isEqualTo("929.02");
    }

    @Test
    void twoRecoveriesAtOnceResolveEachPendingTransferOnceAndAskTheCoreOnce() throws Exception {
        // Cinco caídas antes del asiento: cada pendiente se consulta ("no existe") y se reenvía una vez
        for (int i = 1; i <= 5; i++) {
            executor.start(UUID.randomUUID(), "h", "oid", anaPen, luisPenNumber, new BigDecimal(i + "0.41"));
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
        assertThat(queryString("select count(*)::text from transfer where status = 'COMPLETED'")).isEqualTo("5");
        assertThat(balance(luisPen)).isEqualTo("1152.05");
        assertThat(coreRequests("/core/consultas")).isEqualTo(5);
        assertThat(coreRequests("/core/asientos")).isEqualTo(5);
    }

    // ---------------------------------------------------------------------------------------------------

    private ValidatableResponse transfer(UUID key, String amount) {
        return TransferTest.transfer(Tokens.customer(Tokens.ANA_OID), key, anaPen, luisPenNumber, amount);
    }

    private String statusOf() {
        return queryString("select status from transfer");
    }

    private boolean needsReview() {
        return Boolean.parseBoolean(queryString("select needs_review::text from transfer"));
    }

    // Cuántas veces el core recibió una petición en esa ruta (el journal de WireMock)
    private static int coreRequests(String url) throws IOException, InterruptedException {
        String body = HTTP.send(HttpRequest.newBuilder(URI.create(AccountsTestResource.coreBankingUrl() + "/__admin/requests/count"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"method\":\"POST\",\"url\":\"" + url + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        Matcher count = Pattern.compile("\"count\"\\s*:\\s*(\\d+)").matcher(body);
        return count.find() ? Integer.parseInt(count.group(1)) : -1;
    }
}
