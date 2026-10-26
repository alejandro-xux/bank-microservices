package com.alejandro.bank.accounts;

import com.alejandro.bank.accounts.domain.AccountNumber;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

// Transferencias, los mismos casos que TransferIntegrationTest de accounts-service (BANK-56): el dinero total nunca
// cambia. Las de concurrencia van por HTTP desde varios hilos: el servicio toma la identidad del token, y un hilo de
// la prueba no tiene ninguna
@QuarkusTest
class TransferTest extends IntegrationTest {

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
        emptyBank();
        anaPen = insertAccount(FakeCustomers.ANA, anaPenNumber, "PEN", "1000.00", "ACTIVE");
        anaUsd = insertAccount(FakeCustomers.ANA, anaUsdNumber, "USD", "500.00", "ACTIVE");
        luisPen = insertAccount(FakeCustomers.LUIS, luisPenNumber, "PEN", "1000.00", "ACTIVE");
        luisBlocked = insertAccount(FakeCustomers.LUIS, luisBlockedNumber, "PEN", "50.00", "BLOCKED");
    }

    // ---- el camino feliz ----

    @Test
    void transferMovesTheMoneyWithTwoMovements() {
        UUID key = UUID.randomUUID();

        transfer(Tokens.customer(Tokens.ANA_OID), key, anaPen, luisPenNumber, "100.00")
                .statusCode(201)
                .header("Idempotent-Replayed", nullValue())
                .body("fromAccountId", equalTo(anaPen.toString()))
                .body("toAccountId", equalTo(luisPen.toString()))
                .body("amount", equalTo("100.00"))
                .body("currency", equalTo("PEN"))
                .body("status", equalTo("COMPLETED"));

        assertThat(balance(anaPen)).isEqualTo("900.00");
        assertThat(balance(luisPen)).isEqualTo("1100.00");
        assertThat(queryString("select requested_by || ' ' || amount from transfer where idempotency_key = ?", key))
                .isEqualTo(Tokens.ANA_OID + " 100.00");
        assertThat(queryString("select string_agg(type || ' ' || amount || ' ' || balance_after, ', ' order by type) "
                + "from movement where transfer_id is not null"))
                .isEqualTo("CREDIT 100.00 1100.00, DEBIT 100.00 900.00");
    }

    @Test
    void adminCanTransferFromAnyCustomersAccount() {
        transfer(Tokens.admin(), UUID.randomUUID(), luisPen, anaPenNumber, "10.00").statusCode(201);

        assertThat(balance(luisPen)).isEqualTo("990.00");
    }

    // ---- idempotencia ----

    @Test
    void retryWithTheSameKeyReturnsTheSameTransferWithoutMovingMoneyAgain() {
        UUID key = UUID.randomUUID();
        String first = transfer(Tokens.customer(Tokens.ANA_OID), key, anaPen, luisPenNumber, "100.00")
                .statusCode(201).extract().asString();

        String retry = transfer(Tokens.customer(Tokens.ANA_OID), key, anaPen, luisPenNumber, "100")
                .statusCode(201).header("Idempotent-Replayed", equalTo("true")).extract().asString();

        assertThat(retry).isEqualTo(first);
        assertThat(balance(anaPen)).isEqualTo("900.00");
        assertThat(transfers()).isEqualTo(1);
    }

    @Test
    void sameKeyWithOtherDataIs409AndMovesNothing() {
        UUID key = UUID.randomUUID();
        transfer(Tokens.customer(Tokens.ANA_OID), key, anaPen, luisPenNumber, "100.00").statusCode(201);

        transfer(Tokens.customer(Tokens.ANA_OID), key, anaPen, luisPenNumber, "900.00")
                .statusCode(409)
                .contentType("application/problem+json")
                .body("title", equalTo("Idempotency-Key en conflicto"));

        assertThat(balance(anaPen)).isEqualTo("900.00");
        assertThat(transfers()).isEqualTo(1);
    }

    @Test
    void doubleClickWithTheSameKeyMovesTheMoneyOnce() throws Exception {
        UUID key = UUID.randomUUID();
        List<ExtractableResponse<Response>> responses = concurrently(2,
                i -> () -> transfer(Tokens.admin(), key, anaPen, luisPenNumber, "100.00").extract());

        assertThat(responses).extracting(r -> r.path("id")).containsOnly(responses.getFirst().<String>path("id"));
        assertThat(responses).filteredOn(r -> "true".equals(r.header("Idempotent-Replayed"))).hasSize(1);
        // La original termina COMPLETED; el reintento devuelve el estado de AHORA: si llegó mientras la original
        // esperaba al core, PENDING (202)
        assertThat(responses).filteredOn(r -> r.header("Idempotent-Replayed") == null)
                .extracting(ExtractableResponse::statusCode).containsOnly(201);
        assertThat(responses).extracting(ExtractableResponse::statusCode).isSubsetOf(201, 202);
        assertThat(balance(anaPen)).isEqualTo("900.00");
        assertThat(transfers()).isEqualTo(1);
    }

    @Test
    void aRejectedTransferDoesNotUseUpTheKey() {
        UUID key = UUID.randomUUID();
        transfer(Tokens.customer(Tokens.ANA_OID), key, anaPen, luisPenNumber, "1500.00")
                .statusCode(422).body("title", equalTo("Fondos insuficientes"));
        update("update account set balance = 2000.00 where id = ?", anaPen);

        // Con fondos, el mismo intento (misma clave) ahora sí se hace
        transfer(Tokens.customer(Tokens.ANA_OID), key, anaPen, luisPenNumber, "1500.00")
                .statusCode(201).header("Idempotent-Replayed", nullValue());
        assertThat(balance(anaPen)).isEqualTo("500.00");
    }

    // ---- concurrencia ----

    @Test
    void twentySimultaneousCrossedTransfersKeepTheTotalAndNeverDeadlock() throws Exception {
        List<Integer> statuses = concurrently(20, i -> () -> (i % 2 == 0
                ? transfer(Tokens.admin(), UUID.randomUUID(), anaPen, luisPenNumber, "10.00")
                : transfer(Tokens.admin(), UUID.randomUUID(), luisPen, anaPenNumber, "10.00")).extract().statusCode());

        // Todas terminaron bien: un deadlock habría respondido 500 en alguna
        assertThat(statuses).hasSize(20).containsOnly(201);
        assertThat(balance(anaPen)).isEqualTo("1000.00");
        assertThat(balance(luisPen)).isEqualTo("1000.00");
        assertThat(totalMoney()).isEqualByComparingTo("2550.00");
        assertThat(movements()).isEqualTo(40);
    }

    @Test
    void twentySimultaneousTransfersFromOneAccountNeverOverdraw() throws Exception {
        // 20 × 80.00 desde 1000.00: alcanzan 12 (960.00); las otras 8 se rechazan por fondos
        List<String> outcomes = concurrently(20, i -> () -> {
            ExtractableResponse<Response> response =
                    transfer(Tokens.admin(), UUID.randomUUID(), anaPen, luisPenNumber, "80.00").extract();
            return response.statusCode() == 201 ? "201" : response.statusCode() + " " + response.path("title");
        });

        assertThat(outcomes).filteredOn("201"::equals).hasSize(12);
        assertThat(outcomes).filteredOn("422 Fondos insuficientes"::equals).hasSize(8);
        assertThat(balance(anaPen)).isEqualTo("40.00");
        assertThat(totalMoney()).isEqualByComparingTo("2550.00");
    }

    // ---- reglas ----

    @Test
    void currencyMismatchIs422() {
        transfer(Tokens.customer(Tokens.ANA_OID), UUID.randomUUID(), anaPen, anaUsdNumber, "10.00")
                .statusCode(422).body("title", equalTo("Transferencia rechazada"));

        assertUnchanged();
    }

    @Test
    void unknownDestinationIs422() {
        transfer(Tokens.customer(Tokens.ANA_OID), UUID.randomUUID(), anaPen, AccountNumber.of(999_999), "10.00")
                .statusCode(422)
                .body("detail", equalTo("La cuenta destino " + AccountNumber.of(999_999) + " no existe"));

        assertUnchanged();
    }

    @Test
    void blockedDestinationIs422() {
        transfer(Tokens.customer(Tokens.ANA_OID), UUID.randomUUID(), anaPen, luisBlockedNumber, "10.00")
                .statusCode(422).body("title", equalTo("Cuenta no activa"));

        assertUnchanged();
    }

    @Test
    void sameAccountIs400() {
        transfer(Tokens.customer(Tokens.ANA_OID), UUID.randomUUID(), anaPen, anaPenNumber, "10.00")
                .statusCode(400).body("title", equalTo("Datos inválidos"));

        assertUnchanged();
    }

    @Test
    void mistypedDestinationNumberIs400WithoutLookingItUp() {
        String typo = luisPenNumber.substring(0, luisPenNumber.length() - 1)
                + ((luisPenNumber.charAt(luisPenNumber.length() - 1) - '0' + 1) % 10);

        transfer(Tokens.customer(Tokens.ANA_OID), UUID.randomUUID(), anaPen, typo, "10.00")
                .statusCode(400).body("errors[0].field", equalTo("toAccountNumber"));
    }

    @Test
    void invalidAmountsAre400() {
        for (String amount : List.of("0", "-1.00", "10.555")) {
            transfer(Tokens.customer(Tokens.ANA_OID), UUID.randomUUID(), anaPen, luisPenNumber, amount)
                    .statusCode(400).body("errors[0].field", equalTo("amount"));
        }
        assertUnchanged();
    }

    @Test
    void aBodyThatIsNotJsonIs400WithTheSameProblemAsSpring() {
        // Hallazgo de la comparación de errores (BANK-103): Quarkus respondía 400 sin cuerpo
        for (String body : List.of("{\"fromAccountId\":", "{", "no-json", "{\"amount\":}", "[1,2")) {
            given().header("Authorization", Tokens.customer(Tokens.ANA_OID)).header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType("application/json").body(body)
                    .when().post("/transfers").then().statusCode(400)
                    .contentType("application/problem+json")
                    .body("title", equalTo("Solicitud mal formada"))
                    .body("detail", equalTo("El cuerpo de la solicitud no es válido"))
                    .body("instance", equalTo("/transfers"));
        }
        assertUnchanged();
    }

    @Test
    void idempotencyKeyIsRequiredAndMustBeAUuid() {
        String body = body(anaPen, luisPenNumber, "10.00");
        given().header("Authorization", Tokens.customer(Tokens.ANA_OID)).contentType("application/json").body(body)
                .when().post("/transfers").then().statusCode(400);
        given().header("Authorization", Tokens.customer(Tokens.ANA_OID)).header("Idempotency-Key", "abc")
                .contentType("application/json").body(body)
                .when().post("/transfers").then().statusCode(400);

        assertUnchanged();
    }

    // ---- quién puede ----

    @Test
    void customerCannotTransferFromAnotherCustomersAccountOrAMissingOne() {
        String foreign = transfer(Tokens.customer(Tokens.ANA_OID), UUID.randomUUID(), luisPen, anaPenNumber, "10.00")
                .statusCode(403).extract().asString();
        String missing = transfer(Tokens.customer(Tokens.ANA_OID), UUID.randomUUID(), UUID.randomUUID(), anaPenNumber, "10.00")
                .statusCode(403).extract().asString();

        assertThat(foreign).isEqualTo(missing);
        assertUnchanged();
    }

    @Test
    void anotherCustomerReplayingAnasKeyGets403NotHerTransfer() {
        UUID key = UUID.randomUUID();
        transfer(Tokens.customer(Tokens.ANA_OID), key, anaPen, luisPenNumber, "100.00").statusCode(201);

        transfer(Tokens.customer(Tokens.LUIS_OID), key, anaPen, luisPenNumber, "100.00").statusCode(403);
    }

    @Test
    void customersServiceDownIs503AndMovesNothing() {
        transfer(Tokens.customer(Tokens.CUSTOMERS_DOWN_OID), UUID.randomUUID(), anaPen, luisPenNumber, "10.00")
                .statusCode(503);

        assertUnchanged();
    }

    // ---- utilidades ----

    static ValidatableResponse transfer(String token, UUID key, UUID from, String toNumber, String amount) {
        return given().header("Authorization", token).header("Idempotency-Key", key.toString())
                .contentType("application/json").body(body(from, toNumber, amount))
                .when().post("/transfers").then();
    }

    static String body(UUID from, String toNumber, String amount) {
        return "{\"fromAccountId\":\"" + from + "\",\"toAccountNumber\":\"" + toNumber + "\",\"amount\":\"" + amount + "\"}";
    }

    // Lanza n tareas a la vez (en hilos virtuales) y devuelve sus resultados
    static <T> List<T> concurrently(int n, IntFunction<Callable<T>> task) throws Exception {
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

    private void assertUnchanged() {
        assertThat(balance(anaPen)).isEqualTo("1000.00");
        assertThat(balance(luisPen)).isEqualTo("1000.00");
        assertThat(transfers()).isZero();
        assertThat(movements()).isZero();
    }

    // PEN + USD de prueba: 1000 + 500 + 1000 + 50
    private BigDecimal totalMoney() {
        return new BigDecimal(queryString("select sum(balance)::text from account"));
    }

    private int transfers() {
        return Integer.parseInt(queryString("select count(*)::text from transfer"));
    }
}
