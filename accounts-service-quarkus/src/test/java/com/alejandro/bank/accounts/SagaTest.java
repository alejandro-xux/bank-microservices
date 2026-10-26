package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.domain.AccountNumber;
import com.alejandro.bank.accounts.service.TransferExecutor;
import com.alejandro.bank.contracts.transfers.v1.TransferFailed;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

// La SAGA de una transferencia contra el mainframe emulado de verdad, los mismos casos que SagaIntegrationTest de
// accounts-service (BANK-78) y sus eventos del outbox (BANK-80, BANK-102). Después de cada caso, la regla global
// del spike BANK-74 (S10): saldos + pendientes = lo que había
@QuarkusTest
class SagaTest extends IntegrationTest {

    // Dinero del banco al empezar cada prueba (dos cuentas PEN de 1000.00)
    private static final BigDecimal TOTAL = new BigDecimal("2000.00");

    @Inject
    TransferExecutor executor;

    UUID anaPen;
    UUID luisPen;
    String anaPenNumber = AccountNumber.of(940_001);
    String luisPenNumber = AccountNumber.of(940_002);

    @BeforeEach
    void accounts() {
        emptyBank();
        anaPen = insertAccount(FakeCustomers.ANA, anaPenNumber, "PEN", "1000.00", "ACTIVE");
        luisPen = insertAccount(FakeCustomers.LUIS, luisPenNumber, "PEN", "1000.00", "ACTIVE");
    }

    @AfterEach
    void theBankNeverCreatesNorLosesMoney() {
        BigDecimal balances = new BigDecimal(queryString("select sum(balance)::text from account"));
        BigDecimal inTransit = new BigDecimal(queryString("select coalesce(sum(amount), 0)::text from transfer where status = 'PENDING'"));
        assertThat(balances.add(inTransit)).as("saldos + pendientes").isEqualByComparingTo(TOTAL);
    }

    @Test
    void whenTheCoreConfirmsTheTransferIsCompletedAndTheDestinationReceivesTheMoney() {
        String id = transfer(UUID.randomUUID(), "100.00")
                .statusCode(201)
                .body("status", equalTo("COMPLETED"))
                .body("resolvedAt", notNullValue())
                .extract().path("id");

        assertThat(balance(anaPen)).isEqualTo("900.00");
        assertThat(balance(luisPen)).isEqualTo("1100.00");
        assertThat(movementTypes(anaPen)).isEqualTo("DEBIT");
        assertThat(movementTypes(luisPen)).isEqualTo("CREDIT");
        assertThat(events()).isEqualTo(1);
        given().header("Authorization", Tokens.customer(Tokens.ANA_OID)).when().get("/transfers/" + id)
                .then().statusCode(200).body("status", equalTo("COMPLETED"));
    }

    @Test
    void whenTheCoreRejectsItTheMoneyGoesBackWithAReversal() throws Exception {
        UUID key = UUID.randomUUID();
        String transferId = transfer(key, "100.13")
                .statusCode(422)
                .contentType("application/problem+json")
                .body("title", equalTo("Transferencia rechazada por el core bancario"))
                .body("status", equalTo(422))
                .body("transferStatus", equalTo("FAILED"))
                .body("reason", equalTo("057"))
                .extract().path("transferId");

        // El débito no se borra: se compensa. El destino nunca recibió nada: no sale TransferCompleted, sale un
        // TransferFailed para avisarle al que envía (BANK-80), en la misma transacción que la compensación
        assertThat(balance(anaPen)).isEqualTo("1000.00");
        assertThat(balance(luisPen)).isEqualTo("1000.00");
        assertThat(movementTypes(anaPen)).isEqualTo("DEBIT, REVERSAL");
        assertThat(movementTypes(luisPen)).isNull();
        assertThat(queryString("select string_agg(event_type, ', ') from outbox")).isEqualTo("TransferFailed");
        TransferFailed failed = TransferFailed.parseFrom(payload());
        assertThat(failed.getTransferId()).isEqualTo(transferId);
        assertThat(failed.getFromAccountNumber()).isEqualTo(anaPenNumber);
        assertThat(failed.getFromCustomerId()).isEqualTo(FakeCustomers.ANA);
        assertThat(failed.getAmount()).isEqualTo("100.13");
        assertThat(failed.getReason()).isEqualTo("057");
        assertThat(queryString("select message_key from outbox")).isEqualTo(anaPen.toString());
        assertThat(queryString("select status from transfer")).isEqualTo("FAILED");
        // La reversa muestra en el historial a qué cuenta iba el dinero
        given().header("Authorization", Tokens.customer(Tokens.ANA_OID)).when().get("/accounts/" + anaPen + "/movements")
                .then().body("content.findAll { it.type == 'REVERSAL' }.counterpartyAccount", hasItem(masked(luisPenNumber)));
        // La clave no se libera: el reintento devuelve el mismo rechazo, sin mover dinero otra vez
        transfer(key, "100.13").statusCode(422).body("transferId", equalTo(transferId));
        assertThat(movementTypes(anaPen)).isEqualTo("DEBIT, REVERSAL");
        assertThat(events()).isEqualTo(1);
    }

    @Test
    void whenTheCoreDoesNotAnswerInTimeTheTransferStaysPendingNotRejected() {
        UUID key = UUID.randomUUID();
        long start = System.nanoTime();

        String id = transfer(key, "100.99")
                .statusCode(202)
                .body("status", equalTo("PENDING"))
                .extract().path("id");

        // El core sí lo registra (responde a los 5 s): compensarlo devolvería un dinero que ya se movió
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3500));
        assertThat(balance(anaPen)).isEqualTo("899.01");
        assertThat(balance(luisPen)).isEqualTo("1000.00");
        assertThat(movementTypes(anaPen)).isEqualTo("DEBIT");
        assertThat(events()).isZero();
        given().header("Authorization", Tokens.admin()).when().get("/transfers/" + id)
                .then().body("status", equalTo("PENDING"));
        // Un reintento devuelve el estado de ahora
        transfer(key, "100.99").statusCode(202).header("Idempotent-Replayed", equalTo("true"));
    }

    @Test
    void whenTheCoreIsDownTheTransferStaysPending() {
        transfer(UUID.randomUUID(), "100.98").statusCode(202).body("status", equalTo("PENDING"));

        assertThat(balance(anaPen)).isEqualTo("899.02");
        assertThat(balance(luisPen)).isEqualTo("1000.00");
    }

    @Test
    void twoProcessesSettlingTheSamePendingTransferCreditItOnce() throws Exception {
        UUID transferId = UUID.fromString(transfer(UUID.randomUUID(), "100.99").statusCode(202).extract().path("id"));

        // Como el orquestador y el recuperador cuando el core por fin confirma
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
        assertThat(movementTypes(luisPen)).isEqualTo("CREDIT");
        assertThat(events()).isEqualTo(1);
    }

    @Test
    void onlyTheOwnerOrTheAdminSeeATransferAndANonexistentOneLooksTheSame() {
        String id = transfer(UUID.randomUUID(), "10.00").statusCode(201).extract().path("id");
        UUID missing = UUID.randomUUID();

        given().header("Authorization", Tokens.customer(Tokens.LUIS_OID)).when().get("/transfers/" + id).then().statusCode(403);
        given().header("Authorization", Tokens.customer(Tokens.ANA_OID)).when().get("/transfers/" + missing).then().statusCode(403);
        given().header("Authorization", Tokens.admin()).when().get("/transfers/" + id).then().statusCode(200);
        given().header("Authorization", Tokens.admin()).when().get("/transfers/" + missing).then().statusCode(404)
                .body("title", equalTo("Recurso no encontrado"));
    }

    // ---------------------------------------------------------------------------------------------------

    private ValidatableResponse transfer(UUID key, String amount) {
        return TransferTest.transfer(Tokens.customer(Tokens.ANA_OID), key, anaPen, luisPenNumber, amount);
    }

    // Los tipos de movimiento de la cuenta en orden ("DEBIT, REVERSAL"); null si no tiene
    private String movementTypes(UUID accountId) {
        return queryString("select string_agg(type, ', ' order by created_at, type) from movement where account_id = ?", accountId);
    }

    private int events() {
        return Integer.parseInt(queryString("select count(*)::text from outbox"));
    }

    private byte[] payload() throws java.sql.SQLException {
        try (var connection = dataSource.getConnection();
             var result = connection.createStatement().executeQuery("select payload from outbox")) {
            result.next();
            return result.getBytes(1);
        }
    }

    private static String masked(String number) {
        return number.substring(0, 4) + "******" + number.substring(10);
    }
}
